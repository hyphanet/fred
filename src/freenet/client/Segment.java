package freenet.client;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.Vector;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;

/**
 * A segment, within a splitfile.
 * Self-starting Runnable.
 * 
 * Does not require locking, because all locking goes through the parent Segment.
 */
public class Segment implements RetryTrackerCallback {

	final short splitfileType;
	final FreenetURI[] dataBlocks;
	final FreenetURI[] checkBlocks;
	final BlockFetcher[] dataBlockStatus;
	final BlockFetcher[] checkBlockStatus;
	final int minFetched;
	private Vector blocksNotTried;
	final SplitFetcher parentFetcher;
	final ArchiveContext archiveContext;
	final FetcherContext fetcherContext;
	final long maxBlockLength;
	final boolean nonFullBlocksAllowed;
	/** Has the segment started to do something? Irreversible. */
	private boolean started;
	/** Has the segment finished processing? Irreversible. */
	private boolean finished;
	/** Bucket to store the data retrieved, after it has been decoded */
	private Bucket decodedData;
	/** Recently completed fetches */
	final LinkedList recentlyCompletedFetches;
	/** Running fetches */
	LinkedList runningFetches;
	/** Fetch context for block fetches */
	final FetcherContext blockFetchContext;
	/** Recursion level */
	final int recursionLevel;
	/** Retry tracker */
	private final RetryTracker tracker;
	private FetchException failureException;
	
	/**
	 * Create a Segment.
	 * @param splitfileType The type of the splitfile.
	 * @param splitfileDataBlocks The data blocks to fetch.
	 * @param splitfileCheckBlocks The check blocks to fetch.
	 */
	public Segment(short splitfileType, FreenetURI[] splitfileDataBlocks, FreenetURI[] splitfileCheckBlocks,
			SplitFetcher fetcher, ArchiveContext actx, FetcherContext fctx, long maxTempLength, boolean useLengths, int recLevel) throws MetadataParseException {
		this.splitfileType = splitfileType;
		dataBlocks = splitfileDataBlocks;
		checkBlocks = splitfileCheckBlocks;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			minFetched = dataBlocks.length;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			minFetched = dataBlocks.length;
		} else throw new MetadataParseException("Unknown splitfile type"+splitfileType);
		tracker = new RetryTracker(fctx.maxSplitfileBlockRetries, splitfileDataBlocks.length, fctx.random, fctx.maxSplitfileThreads, false, this, false);
		// Don't add blocks to tracker yet, because don't want to start fetch yet.
		parentFetcher = fetcher;
		archiveContext = actx;
		fetcherContext = fctx;
		maxBlockLength = maxTempLength;
		nonFullBlocksAllowed = useLengths;
		started = false;
		finished = false;
		decodedData = null;
		dataBlockStatus = new BlockFetcher[dataBlocks.length];
		checkBlockStatus = new BlockFetcher[checkBlocks.length];
		blocksNotTried = new Vector();
		Vector firstSet = new Vector(dataBlocks.length+checkBlocks.length);
		blocksNotTried.add(0, firstSet);
		for(int i=0;i<dataBlocks.length;i++) {
			dataBlockStatus[i] = new BlockFetcher(this, tracker, dataBlocks[i], i, fctx.dontEnterImplicitArchives);
			firstSet.add(dataBlockStatus[i]);
		}
		for(int i=0;i<checkBlocks.length;i++) {
			checkBlockStatus[i] = new BlockFetcher(this, tracker, checkBlocks[i], dataBlockStatus.length + i, fctx.dontEnterImplicitArchives);
			firstSet.add(checkBlockStatus[i]);
		}
		recentlyCompletedFetches = new LinkedList();
		runningFetches = new LinkedList();
		// FIXME be a bit more flexible here depending on flags
		if(useLengths) {
			blockFetchContext = new FetcherContext(fetcherContext, FetcherContext.SPLITFILE_USE_LENGTHS_MASK);
			this.recursionLevel = recLevel;
		} else {
			blockFetchContext = new FetcherContext(fetcherContext, FetcherContext.SPLITFILE_DEFAULT_BLOCK_MASK);
			this.recursionLevel = 0;
		}
		Logger.minor(this, "Created segment: data blocks: "+dataBlocks.length+", check blocks: "+checkBlocks.length+" "+this);
	}

	/**
	 * Is the segment finished? (Either error or fetched and decoded)?
	 */
	public boolean isFinished() {
		return finished;
	}

	/**
	 * If there was an error, throw it now.
	 */
	public void throwError() throws FetchException {
		if(failureException != null)
			throw failureException;
	}

	/**
	 * Return the length of the data, after decoding.
	 */
	public long decodedLength() {
		return decodedData.size();
	}

	/**
	 * Write the decoded data to the given output stream.
	 * Do not write more than the specified number of bytes (unless it is negative,
	 * in which case ignore it).
	 * @return The number of bytes written.
	 * @throws IOException If there was an error reading from the bucket the data is
	 * stored in, or writing to the stream provided.
	 */
	public long writeDecodedDataTo(OutputStream os, long truncateLength) throws IOException {
		long len = decodedData.size();
		if(truncateLength >= 0 && truncateLength < len)
			len = truncateLength;
		BucketTools.copyTo(decodedData, os, Math.min(truncateLength, decodedData.size()));
		return len;
	}

	/**
	 * Return true if the Segment has been started, otherwise false.
	 */
	public boolean isStarted() {
		return started;
	}

	/**
	 * Start the Segment fetching the data. When it has finished fetching, it will
	 * notify the SplitFetcher. Note that we must not start fetching until this
	 * method is called, because of the requirement to not fetch all segments
	 * simultaneously.
	 */
	public void start() {
		started = true;
		for(int i=0;i<dataBlockStatus.length;i++) {
			tracker.addBlock(dataBlockStatus[i]);
		}
		Logger.minor(this, "Added data blocks");
		for(int i=0;i<checkBlockStatus.length;i++) {
			tracker.addBlock(checkBlockStatus[i]);
		}
		tracker.callOnProgress();
		tracker.setFinishOnEmpty();
	}

	/**
	 * How many fetches are running?
	 */
	private int runningFetches() {
		synchronized(runningFetches) {
			return runningFetches.size();
		}
	}

	/**
	 * Once we have enough data to decode, tell parent, and decode it.
	 */
	public void finished(SplitfileBlock[] succeeded, SplitfileBlock[] failed, SplitfileBlock[] fatalErrors) {

		Logger.minor(this, "Finished("+succeeded.length+", "+failed.length+", "+fatalErrors.length+")");
		parentFetcher.gotBlocks(this);
		if(succeeded.length >= minFetched)
			// Not finished yet, need to decode
			try {
				successfulFetch();
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" decoding "+this);
				finished = true;
				failureException = new FetchException(FetchException.INTERNAL_ERROR, t);
				parentFetcher.segmentFinished(this);
			}
		else {
			failureException = new SplitFetchException(failed.length, fatalErrors.length, succeeded.length, minFetched, tracker.getAccumulatedNonFatalErrorCodes().merge(tracker.getAccumulatedFatalErrorCodes()));
			finished = true;
			parentFetcher.segmentFinished(this);
		}
	}

	/**
	 * Successful fetch, do the decode, tell the parent, etc.
	 */
	private void successfulFetch() {
		
		// Now decode
		Logger.minor(this, "Decoding "+this);
		
		FECCodec codec = FECCodec.getCodec(splitfileType, dataBlocks.length, checkBlocks.length);
		try {
			if(splitfileType != Metadata.SPLITFILE_NONREDUNDANT) {
				// FIXME hardcoded block size below.
				codec.decode(dataBlockStatus, checkBlockStatus, 32768, fetcherContext.bucketFactory);
				// Now have all the data blocks (not necessarily all the check blocks)
			}
			
			decodedData = fetcherContext.bucketFactory.makeBucket(-1);
			Logger.minor(this, "Copying data from data blocks");
			OutputStream os = decodedData.getOutputStream();
			for(int i=0;i<dataBlockStatus.length;i++) {
				BlockFetcher status = dataBlockStatus[i];
				Bucket data = status.fetchedData;
				BucketTools.copyTo(data, os, Long.MAX_VALUE);
			}
			Logger.minor(this, "Copied data");
			os.close();
			// Must set finished BEFORE calling parentFetcher.
			// Otherwise a race is possible that might result in it not seeing our finishing.
			finished = true;
			parentFetcher.segmentFinished(this);
		} catch (IOException e) {
			Logger.minor(this, "Caught bucket error?: "+e, e);
			finished = true;
			failureException = new FetchException(FetchException.BUCKET_ERROR);
			parentFetcher.segmentFinished(this);
			return;
		}
		
		// Now heal
		
		// Encode any check blocks we don't have
		if(codec != null) {
			try {
				codec.encode(dataBlockStatus, checkBlockStatus, 32768, fetcherContext.bucketFactory);
			} catch (IOException e) {
				Logger.error(this, "Bucket error while healing: "+e, e);
			}
		}
		
		// Now insert *ALL* blocks on which we had at least one failure, and didn't eventually succeed
		for(int i=0;i<dataBlockStatus.length;i++) {
			BlockFetcher block = dataBlockStatus[i];
			if(block.actuallyFetched) continue;
			if(block.completedTries == 0) {
				// 80% chance of not inserting, if we never tried it
				if(fetcherContext.random.nextInt(5) == 0) continue;
			}
			block.queueHeal();
		}
		
		// FIXME heal check blocks too
	}

	public void onProgress() {
		parentFetcher.onProgress();
	}

	public int fetchedBlocks() {
		return tracker.succeededBlocksLength();
	}

	public int failedBlocks() {
		return tracker.failedBlocks().length;
	}

	public int fatallyFailedBlocks() {
		return tracker.fatalErrorBlocks().length;
	}

	public int runningBlocks() {
		return tracker.runningBlocks().length;
	}
}
