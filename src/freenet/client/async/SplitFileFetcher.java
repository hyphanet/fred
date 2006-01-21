package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;

/**
 * Fetch a splitfile, decompress it if need be, and return it to the GetCompletionCallback.
 * Most of the work is done by the segments, and we do not need a thread.
 */
public class SplitFileFetcher extends ClientGetState {

	final FetcherContext fetchContext;
	final ArchiveContext archiveContext;
	final LinkedList decompressors;
	final ClientMetadata clientMetadata;
	final ClientGet parent;
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
	/** The splitfile data blocks. */
	final FreenetURI[] splitfileDataBlocks;
	/** The splitfile check blocks. */
	final FreenetURI[] splitfileCheckBlocks;
	/** Maximum temporary length */
	final long maxTempLength;
	/** Have all segments finished? Access synchronized. */
	private boolean allSegmentsFinished = false;
	/** Override length. If this is positive, truncate the splitfile to this length. */
	private final long overrideLength;
	/** Accept non-full splitfile chunks? */
	private final boolean splitUseLengths;
	private boolean finished;
	
	public SplitFileFetcher(Metadata metadata, GetCompletionCallback rcb, ClientGet parent,
			FetcherContext newCtx, LinkedList decompressors, ClientMetadata clientMetadata, 
			ArchiveContext actx, int recursionLevel) throws FetchException, MetadataParseException {
		this.finished = false;
		this.fetchContext = newCtx;
		this.archiveContext = actx;
		this.decompressors = decompressors;
		this.clientMetadata = clientMetadata;
		this.cb = rcb;
		this.recursionLevel = recursionLevel + 1;
		this.parent = parent;
		if(parent.isCancelled())
			throw new FetchException(FetchException.CANCELLED);
		overrideLength = metadata.dataLength();
		this.splitfileType = metadata.getSplitfileType();
		splitfileDataBlocks = metadata.getSplitfileDataKeys();
		splitfileCheckBlocks = metadata.getSplitfileCheckKeys();
		splitUseLengths = metadata.splitUseLengths();
		int blockLength = splitUseLengths ? -1 : NodeCHK.BLOCK_SIZE;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			// Don't need to do much - just fetch everything and piece it together.
			blocksPerSegment = -1;
			checkBlocksPerSegment = -1;
			segmentCount = 1;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			byte[] params = metadata.splitfileParams();
			if(params == null || params.length < 8)
				throw new MetadataParseException("No splitfile params");
			blocksPerSegment = Fields.bytesToInt(params, 0);
			checkBlocksPerSegment = Fields.bytesToInt(params, 4);
			if(blocksPerSegment > fetchContext.maxDataBlocksPerSegment
					|| checkBlocksPerSegment > fetchContext.maxCheckBlocksPerSegment)
				throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
			segmentCount = (splitfileDataBlocks.length / blocksPerSegment) +
				(splitfileDataBlocks.length % blocksPerSegment == 0 ? 0 : 1);
			// Onion, 128/192.
			// Will be segmented.
		} else throw new MetadataParseException("Unknown splitfile format: "+splitfileType);
		this.maxTempLength = fetchContext.maxTempLength;
		Logger.minor(this, "Algorithm: "+splitfileType+", blocks per segment: "+blocksPerSegment+", check blocks per segment: "+checkBlocksPerSegment+", segments: "+segmentCount);
		segments = new SplitFileFetcherSegment[segmentCount]; // initially null on all entries
		if(segmentCount == 1) {
			segments[0] = new SplitFileFetcherSegment(splitfileType, splitfileDataBlocks, splitfileCheckBlocks, this, archiveContext, fetchContext, maxTempLength, splitUseLengths, recursionLevel);
		} else {
			int dataBlocksPtr = 0;
			int checkBlocksPtr = 0;
			for(int i=0;i<segments.length;i++) {
				// Create a segment. Give it its keys.
				int copyDataBlocks = Math.min(splitfileDataBlocks.length - dataBlocksPtr, blocksPerSegment);
				int copyCheckBlocks = Math.min(splitfileCheckBlocks.length - checkBlocksPtr, checkBlocksPerSegment);
				FreenetURI[] dataBlocks = new FreenetURI[copyDataBlocks];
				FreenetURI[] checkBlocks = new FreenetURI[copyCheckBlocks];
				if(copyDataBlocks > 0)
					System.arraycopy(splitfileDataBlocks, dataBlocksPtr, dataBlocks, 0, copyDataBlocks);
				if(copyCheckBlocks > 0)
					System.arraycopy(splitfileCheckBlocks, checkBlocksPtr, checkBlocks, 0, copyCheckBlocks);
				dataBlocksPtr += copyDataBlocks;
				checkBlocksPtr += copyCheckBlocks;
				segments[i] = new SplitFileFetcherSegment(splitfileType, dataBlocks, checkBlocks, this, archiveContext, fetchContext, maxTempLength, splitUseLengths, recursionLevel+1);
			}
		}
	}

	/** Return the final status of the fetch. Throws an exception, or returns a 
	 * Bucket containing the fetched data.
	 * @throws FetchException If the fetch failed for some reason.
	 */
	private Bucket finalStatus() throws FetchException {
		long finalLength = 0;
		for(int i=0;i<segments.length;i++) {
			SplitFileFetcherSegment s = segments[i];
			if(!s.isFinished()) throw new IllegalStateException("Not all finished");
			s.throwError();
			// If still here, it succeeded
			finalLength += s.decodedLength();
			// Healing is done by Segment
		}
		if(finalLength > overrideLength)
			finalLength = overrideLength;
		
		long bytesWritten = 0;
		OutputStream os = null;
		Bucket output;
		try {
			output = fetchContext.bucketFactory.makeBucket(finalLength);
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
		return output;
	}

	public void segmentFinished(SplitFileFetcherSegment segment) {
		Logger.minor(this, "Finished segment: "+segment);
		synchronized(this) {
			boolean allDone = true;
			for(int i=0;i<segments.length;i++)
				if(!segments[i].isFinished()) {
					Logger.minor(this, "Segment "+segments[i]+" is not finished");
					allDone = false;
				}
			if(allDone) {
				if(allSegmentsFinished)
					Logger.error(this, "Was already finished! (segmentFinished("+segment+")");
				else {
					allSegmentsFinished = true;
					finish();
				}
			}
			notifyAll();
		}
	}

	private void finish() {
		try {
			synchronized(this) {
				if(finished) {
					Logger.error(this, "Was already finished");
					return;
				}
				finished = true;
			}
			Bucket data = finalStatus();
			// Decompress
			while(!decompressors.isEmpty()) {
				Compressor c = (Compressor) decompressors.removeLast();
				try {
					data = c.decompress(data, fetchContext.bucketFactory, Math.max(fetchContext.maxTempLength, fetchContext.maxOutputLength));
				} catch (IOException e) {
					cb.onFailure(new FetchException(FetchException.BUCKET_ERROR, e), this);
					return;
				} catch (CompressionOutputSizeException e) {
					cb.onFailure(new FetchException(FetchException.TOO_BIG, e), this);
					return;
				}
			}
			cb.onSuccess(new FetchResult(clientMetadata, data), this);
		} catch (FetchException e) {
			cb.onFailure(e, this);
		}
	}

	public ClientGet getParent() {
		return parent;
	}

	public void onProgress() {
		int totalBlocks = splitfileDataBlocks.length;
		int fetchedBlocks = 0;
		int failedBlocks = 0;
		int fatallyFailedBlocks = 0;
		int runningBlocks = 0;
		for(int i=0;i<segments.length;i++) {
			SplitFileFetcherSegment segment = segments[i];
			Logger.minor(this, "Segment: "+segment+": fetched="+segment.fetchedBlocks()+", failedBlocks: "+segment.failedBlocks()+
					", fatally: "+segment.fatallyFailedBlocks()+", running: "+segment.runningBlocks());
			fetchedBlocks += segment.fetchedBlocks();
			failedBlocks += segment.failedBlocks();
			fatallyFailedBlocks += segment.fatallyFailedBlocks();
			runningBlocks += segment.runningBlocks();
		}
		fetchContext.eventProducer.produceEvent(new SplitfileProgressEvent(totalBlocks, fetchedBlocks, failedBlocks, fatallyFailedBlocks, runningBlocks));
	}

	public void schedule() {
		for(int i=0;i<segments.length;i++) {
			segments[i].schedule();
		}
	}

}
