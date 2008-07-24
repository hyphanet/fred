/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHK;
import freenet.keys.NodeCHK;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.api.Bucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;

/**
 * Fetch a splitfile, decompress it if need be, and return it to the GetCompletionCallback.
 * Most of the work is done by the segments, and we do not need a thread.
 */
public class SplitFileFetcher implements ClientGetState {

	final FetchContext fetchContext;
	final ArchiveContext archiveContext;
	final ArrayList decompressors;
	final ClientMetadata clientMetadata;
	final ClientRequester parent;
	final GetCompletionCallback cb;
	final int recursionLevel;
	/** The splitfile type. See the SPLITFILE_ constants on Metadata. */
	final short splitfileType;
	/** The segment length. -1 means not segmented and must get everything to decode. */
	final int blocksPerSegment;
	/** The segment length in check blocks. */
	final int checkBlocksPerSegment;
	/** Total number of segments */
	final int segmentCount;
	/** The detailed information on each segment */
	final SplitFileFetcherSegment[] segments;
	/** Maximum temporary length */
	final long maxTempLength;
	/** Have all segments finished? Access synchronized. */
	private boolean allSegmentsFinished;
	/** Override length. If this is positive, truncate the splitfile to this length. */
	private final long overrideLength;
	/** Preferred bucket to return data in */
	private final Bucket returnBucket;
	private boolean finished;
	private long token;
	final boolean persistent;
	
	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.
	
	private final int hashCode;
	
	public int hashCode() {
		return hashCode;
	}
	
	public SplitFileFetcher(Metadata metadata, GetCompletionCallback rcb, ClientRequester parent2,
			FetchContext newCtx, ArrayList decompressors2, ClientMetadata clientMetadata, 
			ArchiveContext actx, int recursionLevel, Bucket returnBucket, long token2, ObjectContainer container, ClientContext context) throws FetchException, MetadataParseException {
		this.persistent = parent2.persistent();
		this.hashCode = super.hashCode();
		this.finished = false;
		this.returnBucket = returnBucket;
		this.fetchContext = newCtx;
		this.archiveContext = actx;
		this.decompressors = decompressors2;
		this.clientMetadata = clientMetadata;
		this.cb = rcb;
		this.recursionLevel = recursionLevel + 1;
		this.parent = parent2;
		if(parent2.isCancelled())
			throw new FetchException(FetchException.CANCELLED);
		overrideLength = metadata.dataLength();
		this.splitfileType = metadata.getSplitfileType();
		ClientCHK[] splitfileDataBlocks = metadata.getSplitfileDataKeys();
		ClientCHK[] splitfileCheckBlocks = metadata.getSplitfileCheckKeys();
		for(int i=0;i<splitfileDataBlocks.length;i++)
			if(splitfileDataBlocks[i] == null) throw new MetadataParseException("Null: data block "+i+" of "+splitfileDataBlocks.length);
		for(int i=0;i<splitfileCheckBlocks.length;i++)
			if(splitfileCheckBlocks[i] == null) throw new MetadataParseException("Null: check block "+i+" of "+splitfileCheckBlocks.length);
		long finalLength = 1L * splitfileDataBlocks.length * CHKBlock.DATA_LENGTH;
		if(finalLength > overrideLength) {
			if(finalLength - overrideLength > CHKBlock.DATA_LENGTH)
				throw new FetchException(FetchException.INVALID_METADATA, "Splitfile is "+finalLength+" but length is "+finalLength);
			finalLength = overrideLength;
		}
		long eventualLength = Math.max(overrideLength, metadata.uncompressedDataLength());
		cb.onExpectedSize(eventualLength, container);
		String mimeType = metadata.getMIMEType();
		if(mimeType != null)
			cb.onExpectedMIME(mimeType, container);
		if(metadata.uncompressedDataLength() > 0)
			cb.onFinalizedMetadata(container);
		if(eventualLength > 0 && newCtx.maxOutputLength > 0 && eventualLength > newCtx.maxOutputLength)
			throw new FetchException(FetchException.TOO_BIG, eventualLength, true, clientMetadata.getMIMEType());
		
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			// Don't need to do much - just fetch everything and piece it together.
			blocksPerSegment = -1;
			checkBlocksPerSegment = -1;
			segmentCount = 1;
			if(splitfileCheckBlocks.length > 0) {
				Logger.error(this, "Splitfile type is SPLITFILE_NONREDUNDANT yet "+splitfileCheckBlocks.length+" check blocks found!!");
				splitfileCheckBlocks = new ClientCHK[0];
			}
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			byte[] params = metadata.splitfileParams();
			if((params == null) || (params.length < 8))
				throw new MetadataParseException("No splitfile params");
			blocksPerSegment = Fields.bytesToInt(params, 0);
			int checkBlocks = Fields.bytesToInt(params, 4);
			
			// FIXME remove this eventually. Will break compat with a few files inserted between 1135 and 1136.
			// Work around a bug around build 1135.
			// We were splitting as (128,255), but we were then setting the checkBlocksPerSegment to 64.
			// Detect this.
			if(checkBlocks == 64 && blocksPerSegment == 128 &&
					splitfileCheckBlocks.length == splitfileDataBlocks.length - (splitfileDataBlocks.length / 128)) {
				Logger.normal(this, "Activating 1135 wrong check blocks per segment workaround for "+this);
				checkBlocks = 127;
			}
			checkBlocksPerSegment = checkBlocks;
			
			if((blocksPerSegment > fetchContext.maxDataBlocksPerSegment)
					|| (checkBlocksPerSegment > fetchContext.maxCheckBlocksPerSegment))
				throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
			segmentCount = (splitfileDataBlocks.length / blocksPerSegment) +
				(splitfileDataBlocks.length % blocksPerSegment == 0 ? 0 : 1);
			// Onion, 128/192.
			// Will be segmented.
		} else throw new MetadataParseException("Unknown splitfile format: "+splitfileType);
		this.maxTempLength = fetchContext.maxTempLength;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Algorithm: "+splitfileType+", blocks per segment: "+blocksPerSegment+
					", check blocks per segment: "+checkBlocksPerSegment+", segments: "+segmentCount+
					", data blocks: "+splitfileDataBlocks.length+", check blocks: "+splitfileCheckBlocks.length);
		segments = new SplitFileFetcherSegment[segmentCount]; // initially null on all entries
		if(segmentCount == 1) {
			// splitfile* will be overwritten, this is bad
			// so copy them
			ClientCHK[] newSplitfileDataBlocks = new ClientCHK[splitfileDataBlocks.length];
			ClientCHK[] newSplitfileCheckBlocks = new ClientCHK[splitfileCheckBlocks.length];
			System.arraycopy(splitfileDataBlocks, 0, newSplitfileDataBlocks, 0, splitfileDataBlocks.length);
			if(splitfileCheckBlocks.length > 0)
				System.arraycopy(splitfileCheckBlocks, 0, newSplitfileCheckBlocks, 0, splitfileCheckBlocks.length);
			segments[0] = new SplitFileFetcherSegment(splitfileType, newSplitfileDataBlocks, newSplitfileCheckBlocks, 
					this, archiveContext, fetchContext, maxTempLength, recursionLevel, parent);
			if(persistent) {
				container.set(segments[0]);
				segments[0].deactivateKeys(container);
				container.deactivate(segments[0], 1);
			}
		} else {
			int dataBlocksPtr = 0;
			int checkBlocksPtr = 0;
			for(int i=0;i<segments.length;i++) {
				// Create a segment. Give it its keys.
				int copyDataBlocks = Math.min(splitfileDataBlocks.length - dataBlocksPtr, blocksPerSegment);
				int copyCheckBlocks = Math.min(splitfileCheckBlocks.length - checkBlocksPtr, checkBlocksPerSegment);
				ClientCHK[] dataBlocks = new ClientCHK[copyDataBlocks];
				ClientCHK[] checkBlocks = new ClientCHK[copyCheckBlocks];
				if(copyDataBlocks > 0)
					System.arraycopy(splitfileDataBlocks, dataBlocksPtr, dataBlocks, 0, copyDataBlocks);
				if(copyCheckBlocks > 0)
					System.arraycopy(splitfileCheckBlocks, checkBlocksPtr, checkBlocks, 0, copyCheckBlocks);
				dataBlocksPtr += copyDataBlocks;
				checkBlocksPtr += copyCheckBlocks;
				segments[i] = new SplitFileFetcherSegment(splitfileType, dataBlocks, checkBlocks, this, archiveContext, 
						fetchContext, maxTempLength, recursionLevel+1, parent);
				if(persistent) {
					container.set(segments[i]);
					segments[i].deactivateKeys(container);
					container.deactivate(segments[i], 1);
				}
			}
			if(dataBlocksPtr != splitfileDataBlocks.length)
				throw new FetchException(FetchException.INVALID_METADATA, "Unable to allocate all data blocks to segments - buggy or malicious inserter");
			if(checkBlocksPtr != splitfileCheckBlocks.length)
				throw new FetchException(FetchException.INVALID_METADATA, "Unable to allocate all check blocks to segments - buggy or malicious inserter");
		}
		this.token = token2;
		parent.addBlocks(splitfileDataBlocks.length + splitfileCheckBlocks.length, container);
		parent.addMustSucceedBlocks(splitfileDataBlocks.length, container);
		parent.notifyClients(container, context);
	}

	/** Return the final status of the fetch. Throws an exception, or returns a 
	 * Bucket containing the fetched data.
	 * @throws FetchException If the fetch failed for some reason.
	 */
	private Bucket finalStatus(ObjectContainer container, ClientContext context) throws FetchException {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long finalLength = 0;
		for(int i=0;i<segments.length;i++) {
			SplitFileFetcherSegment s = segments[i];
			if(persistent)
				container.activate(s, 1);
			if(!s.succeeded()) {
				throw new IllegalStateException("Not all finished");
			}
			s.throwError();
			// If still here, it succeeded
			finalLength += s.decodedLength();
			if(logMINOR)
				Logger.minor(this, "Segment "+i+" decoded length "+s.decodedLength()+" total length now "+finalLength+" for "+s.dataBuckets.length+" blocks which should be "+(s.dataBuckets.length * NodeCHK.BLOCK_SIZE));
			// Healing is done by Segment
		}
		if(finalLength > overrideLength) {
			if(finalLength - overrideLength > CHKBlock.DATA_LENGTH)
				throw new FetchException(FetchException.INVALID_METADATA, "Splitfile is "+finalLength+" but length is "+finalLength);
			finalLength = overrideLength;
		}
		
		long bytesWritten = 0;
		OutputStream os = null;
		Bucket output;
		if(persistent) {
			container.activate(decompressors, 5);
			if(returnBucket != null)
				container.activate(returnBucket, 5);
		}
		try {
			if((returnBucket != null) && decompressors.isEmpty()) {
				output = returnBucket;
				if(persistent)
					container.activate(output, 5);
			} else
				output = context.getBucketFactory(parent.persistent()).makeBucket(finalLength);
			os = output.getOutputStream();
			for(int i=0;i<segments.length;i++) {
				SplitFileFetcherSegment s = segments[i];
				long max = (finalLength < 0 ? 0 : (finalLength - bytesWritten));
				bytesWritten += s.writeDecodedDataTo(os, max);
			}
		} catch (IOException e) {
			throw new FetchException(FetchException.BUCKET_ERROR, e);
		} finally {
			if(os != null) {
				try {
					os.close();
				} catch (IOException e) {
					// If it fails to close it may return corrupt data.
					throw new FetchException(FetchException.BUCKET_ERROR, e);
				}
			}
		}
		if(finalLength != output.size()) {
			Logger.error(this, "Final length is supposed to be "+finalLength+" but only written "+output.size());
		}
		return output;
	}

	public void segmentFinished(SplitFileFetcherSegment segment, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Finished segment: "+segment);
		boolean finish = false;
		synchronized(this) {
			boolean allDone = true;
			for(int i=0;i<segments.length;i++) {
				if(persistent)
					container.activate(segments[i], 1);
				if(!segments[i].succeeded()) {
					if(logMINOR) Logger.minor(this, "Segment "+segments[i]+" is not finished");
					allDone = false;
				}
			}
			if(allDone) {
				if(allSegmentsFinished) {
					Logger.error(this, "Was already finished! (segmentFinished("+segment+ ')', new Exception("debug"));
				} else {
					allSegmentsFinished = true;
					finish = true;
				}
			} else {
				for(int i=0;i<segments.length;i++) {
					if(segments[i] == segment) continue;
					container.deactivate(segments[i], 1);
				}
			}
			notifyAll();
		}
		if(persistent) container.set(this);
		if(finish) finish(container, context);
	}

	private void finish(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(cb, 1);
		}
		try {
			synchronized(this) {
				if(finished) {
					Logger.error(this, "Was already finished");
					return;
				}
				finished = true;
			}
			Bucket data = finalStatus(container, context);
			// Decompress
			if(persistent) {
				container.set(this);
				container.activate(decompressors, 5);
				container.activate(returnBucket, 5);
				container.activate(cb, 1);
				container.activate(fetchContext, 1);
			}
			while(!decompressors.isEmpty()) {
				Compressor c = (Compressor) decompressors.remove(decompressors.size()-1);
				long maxLen = Math.max(fetchContext.maxTempLength, fetchContext.maxOutputLength);
				try {
					Bucket out = returnBucket;
					if(!decompressors.isEmpty()) out = null;
					data = c.decompress(data, context.getBucketFactory(parent.persistent()), maxLen, maxLen * 4, out);
				} catch (IOException e) {
					cb.onFailure(new FetchException(FetchException.BUCKET_ERROR, e), this, container, context);
					return;
				} catch (CompressionOutputSizeException e) {
					if(Logger.shouldLog(Logger.MINOR, this))
						Logger.minor(this, "Too big: maxSize = "+fetchContext.maxOutputLength+" maxTempSize = "+fetchContext.maxTempLength);
					cb.onFailure(new FetchException(FetchException.TOO_BIG, e.estimatedSize, false /* FIXME */, clientMetadata.getMIMEType()), this, container, context);
					return;
				}
			}
			cb.onSuccess(new FetchResult(clientMetadata, data), this, container, context);
		} catch (FetchException e) {
			cb.onFailure(e, this, container, context);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
			System.err.println("Failing above attempted fetch...");
			cb.onFailure(new FetchException(FetchException.INTERNAL_ERROR, e), this, container, context);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), this, container, context);
		}
	}

	public void schedule(ObjectContainer container, ClientContext context, boolean regmeOnly) {
		if(persistent)
			container.activate(this, 1);
		if(segments.length > 1)
			regmeOnly = true;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Scheduling "+this);
		for(int i=0;i<segments.length;i++) {
			if(logMINOR)
				Logger.minor(this, "Scheduling segment "+i+" : "+segments[i]);
			if(persistent)
				container.activate(segments[i], 1);
			segments[i].schedule(container, context, regmeOnly);
			if(persistent)
				container.deactivate(segments[i], 1);
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		for(int i=0;i<segments.length;i++) {
			if(logMINOR)
				Logger.minor(this, "Cancelling segment "+i);
			container.activate(segments[i], 1);
			segments[i].cancel(container, context);
		}
	}

	public long getToken() {
		return token;
	}

}
