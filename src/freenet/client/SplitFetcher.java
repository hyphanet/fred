package freenet.client;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.FECCodeFactory;

import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.Logger;

/**
 * Class to fetch a splitfile.
 */
public class SplitFetcher {

	// 128/192. Crazy, but it's possible we'd get big erasures.
	static final int ONION_STD_K = 128;
	static final int ONION_STD_N = 192;
	
	/** The standard onion codec */
	static FECCode onionStandardCode =
		FECCodeFactory.getDefault().createFECCode(ONION_STD_K,ONION_STD_N);
	
	/** The splitfile type. See the SPLITFILE_ constants on Metadata. */
	final short splitfileType;
	/** The segment length. -1 means not segmented and must get everything to decode. */
	final int blocksPerSegment;
	/** The segment length in check blocks. */
	final int checkBlocksPerSegment;
	/** Total number of segments */
	final int segmentCount;
	/** The detailed information on each segment */
	final Segment[] segments;
	/** The splitfile data blocks. */
	final FreenetURI[] splitfileDataBlocks;
	/** The splitfile check blocks. */
	final FreenetURI[] splitfileCheckBlocks;
	/** The archive context */
	final ArchiveContext actx;
	/** The fetch context */
	final FetcherContext fctx;
	/** Maximum temporary length */
	final long maxTempLength;
	/** Have all segments finished? Access synchronized. */
	private boolean allSegmentsFinished = false;
	/** Currently fetching segment */
	private Segment fetchingSegment;
	/** Array of unstarted segments. Modify synchronized. */
	private Vector unstartedSegments;
	/** Override length. If this is positive, truncate the splitfile to this length. */
	private long overrideLength;
	/** Accept non-full splitfile chunks? */
	private boolean splitUseLengths;
	
	public SplitFetcher(Metadata metadata, ArchiveContext archiveContext, FetcherContext ctx, int recursionLevel) throws MetadataParseException, FetchException {
		actx = archiveContext;
		fctx = ctx;
		overrideLength = metadata.dataLength;
		this.maxTempLength = ctx.maxTempLength;
		splitfileType = metadata.getSplitfileType();
		splitfileDataBlocks = metadata.getSplitfileDataKeys();
		splitfileCheckBlocks = metadata.getSplitfileCheckKeys();
		splitUseLengths = metadata.splitUseLengths;
		int blockLength = splitUseLengths ? -1 : NodeCHK.BLOCK_SIZE;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			// Don't need to do much - just fetch everything and piece it together.
			blocksPerSegment = -1;
			checkBlocksPerSegment = -1;
			segmentCount = 1;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			byte[] params = metadata.splitfileParams;
			if(params == null || params.length < 8)
				throw new MetadataParseException("No splitfile params");
			blocksPerSegment = Fields.bytesToInt(params, 0);
			checkBlocksPerSegment = Fields.bytesToInt(params, 4);
			if(blocksPerSegment > ctx.maxDataBlocksPerSegment
					|| checkBlocksPerSegment > ctx.maxCheckBlocksPerSegment)
				throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
			segmentCount = (splitfileDataBlocks.length / blocksPerSegment) +
				(splitfileDataBlocks.length % blocksPerSegment == 0 ? 0 : 1);
			// Onion, 128/192.
			// Will be segmented.
		} else throw new MetadataParseException("Unknown splitfile format: "+splitfileType);
		Logger.minor(this, "Algorithm: "+splitfileType+", blocks per segment: "+blocksPerSegment+", check blocks per segment: "+checkBlocksPerSegment+", segments: "+segmentCount);
		segments = new Segment[segmentCount]; // initially null on all entries
		if(segmentCount == 1) {
			segments[0] = new Segment(splitfileType, splitfileDataBlocks, splitfileCheckBlocks, this, archiveContext, ctx, maxTempLength, splitUseLengths, recursionLevel+1);
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
				segments[i] = new Segment(splitfileType, dataBlocks, checkBlocks, this, archiveContext, ctx, maxTempLength, splitUseLengths, blockLength);
			}
		}
		unstartedSegments = new Vector();
		for(int i=0;i<segments.length;i++)
			unstartedSegments.add(segments[i]);
		Logger.minor(this, "Segments: "+unstartedSegments.size()+", data keys: "+splitfileDataBlocks.length+", check keys: "+(splitfileCheckBlocks==null?0:splitfileCheckBlocks.length));
	}

	/**
	 * Fetch the splitfile.
	 * Fetch one segment, while decoding the previous one.
	 * Fetch the segments in random order.
	 * When everything has been fetched and decoded, return the full data.
	 * @throws FetchException 
	 */
	public Bucket fetch() throws FetchException {
		/*
		 * While(true) {
		 * 	Pick a random segment, start it fetching.
		 * 	Wait for a segment to finish fetching, a segment to finish decoding, or an error.
		 * 	If a segment finishes fetching:
		 * 		Continue to start another one if there are any left
		 * 	If a segment finishes decoding:
		 * 		If all segments are decoded, assemble all the segments and return the data.
		 * 
		 * Segments are expected to automatically start decoding when they finish fetching,
		 * but to tell us either way.
		 */
		while(true) {
			synchronized(this) {
				if(fetchingSegment == null) {
					// Pick a random segment
					fetchingSegment = chooseUnstartedSegment();
					if(fetchingSegment == null) {
						// All segments have started
					} else {
						fetchingSegment.start();
					}
				}
				if(allSegmentsFinished) {
					return finalStatus();
				}
				try {
					wait(10*1000); // or wait()?
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}

	private Segment chooseUnstartedSegment() {
		synchronized(unstartedSegments) {
			if(unstartedSegments.isEmpty()) return null;
			int x = fctx.random.nextInt(unstartedSegments.size());
			Logger.minor(this, "Starting segment "+x+" of "+unstartedSegments.size());
			Segment s = (Segment) unstartedSegments.remove(x);
			return s;
		}
	}

	/** Return the final status of the fetch. Throws an exception, or returns a 
	 * Bucket containing the fetched data.
	 * @throws FetchException If the fetch failed for some reason.
	 */
	private Bucket finalStatus() throws FetchException {
		long finalLength = 0;
		for(int i=0;i<segments.length;i++) {
			Segment s = segments[i];
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
			output = fctx.bucketFactory.makeBucket(finalLength);
			os = output.getOutputStream();
			for(int i=0;i<segments.length;i++) {
				Segment s = segments[i];
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

	public void gotBlocks(Segment segment) {
		Logger.minor(this, "Got blocks for segment: "+segment);
		synchronized(this) {
			fetchingSegment = null;
			notifyAll();
		}
	}

	public void segmentFinished(Segment segment) {
		Logger.minor(this, "Finished segment: "+segment);
		synchronized(this) {
			boolean allDone = true;
			for(int i=0;i<segments.length;i++)
				if(!segments[i].isFinished()) {
					Logger.minor(this, "Segment "+segments[i]+" is not finished");
					allDone = false;
				}
			if(allDone) allSegmentsFinished = true;
			notifyAll();
		}
	}

	public void onProgress() {
		int totalBlocks = splitfileDataBlocks.length + splitfileCheckBlocks.length;
		int fetchedBlocks = 0;
		int failedBlocks = 0;
		int fatallyFailedBlocks = 0;
		int runningBlocks = 0;
		for(int i=0;i<segments.length;i++) {
			fetchedBlocks += segments[i].fetchedBlocks();
			failedBlocks += segments[i].failedBlocks();
			fatallyFailedBlocks += segments[i].fatallyFailedBlocks();
			runningBlocks += segments[i].runningBlocks();
		}
		fctx.eventProducer.produceEvent(new SplitfileProgressEvent(totalBlocks, fetchedBlocks, failedBlocks, fatallyFailedBlocks, runningBlocks));
	}

}
