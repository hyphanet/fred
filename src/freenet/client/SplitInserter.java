package freenet.client;

import java.io.IOException;
import java.util.Vector;

import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.compress.Compressor;

/**
 * Insert a splitfile.
 */
public class SplitInserter implements RetryTrackerCallback {

	final Bucket origData;
	final long dataLength;
	final ClientMetadata clientMetadata;
	final short compressionCodec;
	final short splitfileAlgorithm;
	final InserterContext ctx;
	final RetryTracker tracker;
	final int segmentSize;
	final int checkSegmentSize;
	final int blockSize;
	SplitfileBlock[] origDataBlocks;
	InsertSegment encodingSegment;
	InsertSegment[] segments;
	final Vector unstartedSegments = new Vector();
	private boolean allSegmentsFinished = false;
	private int succeeded;
	private int failed;
	private int fatalErrors;
	private int countCheckBlocks;
	private SplitfileBlock[] fatalErrorBlocks;
	private FileInserter inserter;
	
	public SplitInserter(Bucket data, ClientMetadata clientMetadata, Compressor compressor, short splitfileAlgorithm, InserterContext ctx, FileInserter inserter, int blockLength) throws InserterException {
		this.origData = data;
		this.blockSize = blockLength;
		this.clientMetadata = clientMetadata;
		if(compressor == null)
			compressionCodec = -1;
		else
			compressionCodec = compressor.codecNumberForMetadata();
		this.splitfileAlgorithm = splitfileAlgorithm;
		this.ctx = ctx;
		this.dataLength = data.size();
		segmentSize = FECCodec.getCodecMaxSegmentDataBlocks(splitfileAlgorithm);
		checkSegmentSize = FECCodec.getCodecMaxSegmentCheckBlocks(splitfileAlgorithm);
		try {
			splitIntoBlocks();
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR);
		}
		tracker = new RetryTracker(ctx.maxInsertBlockRetries, 0, ctx.random, ctx.maxSplitInsertThreads, true, this);
		this.inserter = inserter;
	}

	/**
	 * Inserts the splitfile.
	 * @return The URI of the resulting file.
	 * @throws InserterException If we are not able to insert the splitfile.
	 */
	public FreenetURI run() throws InserterException {
		startInsertingDataBlocks();
		splitIntoSegments(segmentSize);
		// Backwards, because the last is the shortest
		try {
			for(int i=segments.length-1;i>=0;i--) {
				countCheckBlocks += encodeSegment(i, origDataBlocks.length + checkSegmentSize * i);
			}
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR);
		}
		// Wait for the insertion thread to finish
		return waitForCompletion();
	}

	private FreenetURI waitForCompletion() throws InserterException {
		synchronized(this) {
			while(!allSegmentsFinished) {
				try {
					wait(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}

		// Did we succeed?
		
		if(fatalErrors > 0) {
			throw new InserterException(InserterException.FATAL_ERRORS_IN_BLOCKS, tracker.getAccumulatedFatalErrorCodes());
		}
		
		if(failed > 0) {
			throw new InserterException(InserterException.TOO_MANY_RETRIES_IN_BLOCKS, tracker.getAccumulatedNonFatalErrorCodes());
		}
		
		// Okay, we succeeded... create the manifest
		
		Metadata metadata = new Metadata(splitfileAlgorithm, getDataURIs(), getCheckURIs(), clientMetadata, dataLength, compressionCodec);
		
		Bucket mbucket;
		try {
			mbucket = BucketTools.makeImmutableBucket(ctx.bf, metadata.writeToByteArray());
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR);
		}

		if(inserter == null)
			inserter = new FileInserter(ctx);
		
		InsertBlock mblock = new InsertBlock(mbucket, clientMetadata, FreenetURI.EMPTY_CHK_URI);
		
		return inserter.run(mblock, true);
	}

	private FreenetURI[] getCheckURIs() {
		// Copy check blocks from each segment into a FreenetURI[].
		FreenetURI[] uris = new FreenetURI[countCheckBlocks];
		int x = 0;
		for(int i=0;i<segments.length;i++) {
			FreenetURI[] segURIs = segments[i].getCheckURIs();
			System.arraycopy(segURIs, 0, uris, x, segURIs.length);
			x += segURIs.length;
		}

		if(uris.length != x)
			throw new IllegalStateException("Total is wrong");
		
		return uris;
	}

	private FreenetURI[] getDataURIs() {
		FreenetURI[] uris = new FreenetURI[origDataBlocks.length];
		for(int i=0;i<uris.length;i++)
			uris[i] = origDataBlocks[i].getURI();
		return uris;
	}

	private int encodeSegment(int i, int offset) throws IOException {
		encodingSegment = segments[i];
		return encodingSegment.encode(offset, tracker, ctx);
	}

	/**
	 * Start the insert, by adding all the data blocks.
	 */
	private void startInsertingDataBlocks() {
		for(int i=0;i<origDataBlocks.length;i++)
			tracker.addBlock(origDataBlocks[i]);
	}

	/**
	 * Split blocks into segments for encoding.
	 * @throws IOException If there is a bucket error encoding the file.
	 */
	private void splitIntoBlocks() throws IOException {
		Bucket[] dataBuckets = BucketTools.split(origData, NodeCHK.BLOCK_SIZE, ctx.bf);
		origDataBlocks = new SplitfileBlock[dataBuckets.length];
		for(int i=0;i<origDataBlocks.length;i++) {
			origDataBlocks[i] = new BlockInserter(dataBuckets[i], i, tracker, ctx);
		}
	}

	/**
	 * Group the blocks into segments.
	 */
	private void splitIntoSegments(int segmentSize) {
		int dataBlocks = origDataBlocks.length;

		Vector segs = new Vector();
		
		// First split the data up
		if(dataBlocks < segmentSize || segmentSize == -1) {
			// Single segment
			InsertSegment onlySeg = new InsertSegment(splitfileAlgorithm, origDataBlocks, blockSize, ctx.bf);
			segs.add(onlySeg);
		} else {
			int j = 0;
			for(int i=segmentSize;;i+=segmentSize) {
				if(i > dataBlocks) i = dataBlocks;
				SplitfileBlock[] seg = new SplitfileBlock[i-j];
				System.arraycopy(origDataBlocks, j, seg, 0, i-j);
				unstartedSegments.add(seg);
				j = i;
				segs.add(new InsertSegment(splitfileAlgorithm, seg, blockSize, ctx.bf));
				if(i == dataBlocks) break;
			}
		}
		segments = (InsertSegment[]) unstartedSegments.toArray(new InsertSegment[unstartedSegments.size()]);
	}

	public void finished(SplitfileBlock[] succeeded, SplitfileBlock[] failed, SplitfileBlock[] fatalErrors) {
		synchronized(this) {
			allSegmentsFinished = true;
			this.succeeded = succeeded.length;
			this.failed = failed.length;
			this.fatalErrorBlocks = fatalErrors;
			this.fatalErrors = fatalErrorBlocks.length;
			notify();
		}
	}

}
