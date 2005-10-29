package freenet.client;

import java.io.IOException;
import java.io.OutputStream;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.FECCodeFactory;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;

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
	private Segment[] unstartedSegments;
	/** Number of unstarted segments. Ditto. */
	private int unstartedSegmentsCount;
	/** Override length. If this is positive, truncate the splitfile to this length. */
	private long overrideLength;
	/** Accept non-full splitfile chunks? */
	private boolean splitUseLengths;
	
	public SplitFetcher(Metadata metadata, long maxTempLength, ArchiveContext archiveContext, FetcherContext ctx) throws MetadataParseException {
		actx = archiveContext;
		fctx = ctx;
		overrideLength = metadata.dataLength;
		this.maxTempLength = maxTempLength;
		splitfileType = metadata.getSplitfileType();
		splitfileDataBlocks = metadata.getSplitfileDataKeys();
		splitfileCheckBlocks = metadata.getSplitfileCheckKeys();
		splitUseLengths = metadata.splitUseLengths;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			// Don't need to do much - just fetch everything and piece it together.
			blocksPerSegment = -1;
			checkBlocksPerSegment = -1;
			segmentCount = 1;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			blocksPerSegment = 128;
			checkBlocksPerSegment = 64;
			segmentCount = (splitfileDataBlocks.length / blocksPerSegment) +
				(splitfileDataBlocks.length % blocksPerSegment == 0 ? 0 : 1);
			// Onion, 128/192.
			// Will be segmented.
		} else throw new MetadataParseException("Unknown splitfile format: "+splitfileType);
		segments = new Segment[segmentCount]; // initially null on all entries
		if(segmentCount == 1) {
			segments[0] = new Segment(splitfileType, splitfileDataBlocks, splitfileCheckBlocks, this, archiveContext, ctx, maxTempLength, splitUseLengths);
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
				segments[i] = new Segment(splitfileType, dataBlocks, checkBlocks, this, archiveContext, ctx, maxTempLength, splitUseLengths);
			}
		}
		unstartedSegments = segments;
		unstartedSegmentsCount = segments.length;
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
					Segment s = chooseUnstartedSegment();
					if(s == null) {
						// All segments have started
					} else {
						start(s); // will keep unstartedSegments up to date
					}
				}
				if(allSegmentsFinished) {
					return finalStatus();
				}
				try {
					wait(100*1000); // or wait()?
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}

	private synchronized void start(Segment start) {
		start.start();
		int j = 0;
		for(int i=0;i<unstartedSegmentsCount;i++) {
			Segment s = unstartedSegments[i];
			if(!s.isStarted()) {
				unstartedSegments[j] = unstartedSegments[i];
				j++;
			}
		}
		unstartedSegmentsCount = j;
	}

	private Segment chooseUnstartedSegment() {
		if(unstartedSegmentsCount == 0) return null;
		return unstartedSegments[fctx.random.nextInt(unstartedSegmentsCount)];
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
					throw new FetchException(FetchException.BUCKET_ERROR, e);
				}
			}
		}
		return output;
	}

}
