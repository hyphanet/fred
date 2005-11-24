package freenet.client;

import java.io.IOException;
import java.util.Vector;

import freenet.client.events.GeneratedURIEvent;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
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
	final boolean isMetadata;
	SplitfileBlock[] origDataBlocks;
	InsertSegment encodingSegment;
	InsertSegment[] segments;
	private boolean finishedInserting = false;
	private boolean getCHKOnly;
	private int succeeded;
	private int failed;
	private int fatalErrors;
	private int countCheckBlocks;
	private SplitfileBlock[] fatalErrorBlocks;
	private FileInserter inserter;
	
	public SplitInserter(Bucket data, ClientMetadata clientMetadata, Compressor compressor, short splitfileAlgorithm, InserterContext ctx, FileInserter inserter, int blockLength, boolean getCHKOnly, boolean isMetadata) throws InserterException {
		this.origData = data;
		this.getCHKOnly = getCHKOnly;
		this.blockSize = blockLength;
		this.clientMetadata = clientMetadata;
		if(compressor == null)
			compressionCodec = -1;
		else
			compressionCodec = compressor.codecNumberForMetadata();
		this.splitfileAlgorithm = splitfileAlgorithm;
		this.ctx = ctx;
		this.dataLength = data.size();
		segmentSize = ctx.splitfileSegmentDataBlocks;
		checkSegmentSize = splitfileAlgorithm == Metadata.SPLITFILE_NONREDUNDANT ? 0 : ctx.splitfileSegmentCheckBlocks;
		tracker = new RetryTracker(ctx.maxInsertBlockRetries, Integer.MAX_VALUE, ctx.random, ctx.maxSplitInsertThreads, true, this);
		try {
			splitIntoBlocks();
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR, e, null);
		}
		this.inserter = inserter;
		this.isMetadata = isMetadata;
	}

	/**
	 * Inserts the splitfile.
	 * @return The URI of the resulting file.
	 * @throws InserterException If we are not able to insert the splitfile.
	 */
	public FreenetURI run() throws InserterException {
		try {
			startInsertingDataBlocks();
			splitIntoSegments(segmentSize);
			// Backwards, because the last is the shortest
			try {
				for(int i=segments.length-1;i>=0;i--) {
					encodeSegment(i, origDataBlocks.length + checkSegmentSize * i);
					Logger.minor(this, "Encoded segment "+i+" of "+segments.length);
				}
			} catch (IOException e) {
				throw new InserterException(InserterException.BUCKET_ERROR, e, null);
			}
			// Wait for the insertion thread to finish
			return waitForCompletion();
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			tracker.kill();
			if(t instanceof InserterException) throw (InserterException)t;
			throw new InserterException(InserterException.INTERNAL_ERROR, t, null);
		}
	}

	private FreenetURI waitForCompletion() throws InserterException {
		tracker.setFinishOnEmpty();
		synchronized(this) {
			while(!finishedInserting) {
				try {
					wait(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}

		// Create the manifest (even if we failed, so that the key is visible)

		FreenetURI[] dataURIs = getDataURIs();
		FreenetURI[] checkURIs = getCheckURIs();
		
		Logger.minor(this, "Data URIs: "+dataURIs.length+", check URIs: "+checkURIs.length);
		
		boolean missingURIs = anyNulls(dataURIs) || anyNulls(checkURIs);
		
		if(missingURIs && fatalErrors == 0 && failed == 0)
			throw new IllegalStateException();
		
		FreenetURI uri = null;
		
		if(!missingURIs) {
		
			Metadata metadata = new Metadata(splitfileAlgorithm, dataURIs, checkURIs, segmentSize, checkSegmentSize, clientMetadata, dataLength, compressionCodec, isMetadata);
			
			Bucket mbucket;
			try {
				mbucket = BucketTools.makeImmutableBucket(ctx.bf, metadata.writeToByteArray());
			} catch (IOException e) {
				throw new InserterException(InserterException.BUCKET_ERROR, null);
			}
			
			if(inserter == null)
				inserter = new FileInserter(ctx);
			
			InsertBlock mblock = new InsertBlock(mbucket, clientMetadata, FreenetURI.EMPTY_CHK_URI);
			
			// FIXME probably should uncomment below so it doesn't get inserted at all?
			// FIXME this is a hack for small network support... but we will need that IRL... hmmm
			uri = inserter.run(mblock, true, getCHKOnly/* || (fatalErrors > 0 || failed > 0)*/);
			
		}
		// Did we succeed?
		
		ctx.eventProducer.produceEvent(new GeneratedURIEvent(uri));
		
		if(fatalErrors > 0) {
			throw new InserterException(InserterException.FATAL_ERRORS_IN_BLOCKS, tracker.getAccumulatedFatalErrorCodes(), uri);
		}
		
		if(failed > 0) {
			throw new InserterException(InserterException.TOO_MANY_RETRIES_IN_BLOCKS, tracker.getAccumulatedNonFatalErrorCodes(), uri);
		}
		
		return uri;
	}

	// FIXME move this to somewhere
	private static boolean anyNulls(Object[] array) {
		for(int i=0;i<array.length;i++)
			if(array[i] == null) return true;
		return false;
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
			origDataBlocks[i] = new BlockInserter(dataBuckets[i], i, tracker, ctx, getCHKOnly);
			if(origDataBlocks[i].getData() == null)
				throw new NullPointerException("Block "+i+" of "+dataBuckets.length+" is null");
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
			InsertSegment onlySeg = new InsertSegment(splitfileAlgorithm, origDataBlocks, blockSize, ctx.bf, getCHKOnly, 0);
			segs.add(onlySeg);
		} else {
			int j = 0;
			int segNo = 0;
			for(int i=segmentSize;;i+=segmentSize) {
				if(i > dataBlocks) i = dataBlocks;
				SplitfileBlock[] seg = new SplitfileBlock[i-j];
				System.arraycopy(origDataBlocks, j, seg, 0, i-j);
				j = i;
				for(int x=0;x<seg.length;x++)
					if(seg[x].getData() == null) throw new NullPointerException("In splitIntoSegs: "+x+" is null of "+seg.length+" of "+segNo);
				InsertSegment s = new InsertSegment(splitfileAlgorithm, seg, blockSize, ctx.bf, getCHKOnly, segNo);
				countCheckBlocks += s.checkBlocks.length;
				segs.add(s);
				
				if(i == dataBlocks) break;
				segNo++;
			}
		}
		segments = (InsertSegment[]) segs.toArray(new InsertSegment[segs.size()]);
	}

	public void finished(SplitfileBlock[] succeeded, SplitfileBlock[] failed, SplitfileBlock[] fatalErrors) {
		synchronized(this) {
			finishedInserting = true;
			this.succeeded = succeeded.length;
			this.failed = failed.length;
			this.fatalErrorBlocks = fatalErrors;
			this.fatalErrors = fatalErrorBlocks.length;
			notify();
		}
	}

	public void onProgress() {
		/* What info to report?
		 * - Total number of blocks to insert.
		 * - 
		 */
		int totalBlocks = origDataBlocks.length + countCheckBlocks;
		int fetchedBlocks = tracker.succeededBlocks().length;
		int failedBlocks = tracker.countFailedBlocks();
		int fatallyFailedBlocks = tracker.fatalErrorBlocks().length;
		int runningBlocks = tracker.runningBlocks().length;
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(totalBlocks, fetchedBlocks, failedBlocks, fatallyFailedBlocks, runningBlocks));
	}

}
