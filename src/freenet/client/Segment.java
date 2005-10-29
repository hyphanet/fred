package freenet.client;

import java.io.IOException;
import java.io.InputStream;
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
public class Segment implements Runnable {

	public class BlockStatus implements Runnable {

		final FreenetURI uri;
		int completedTries;
		
		public BlockStatus(FreenetURI freenetURI) {
			uri = freenetURI;
			completedTries = 0;
		}

		public void startFetch() {
			synchronized(runningFetches) {
				runningFetches.add(this);
				try {
					Thread t = new Thread(this);
					t.setDaemon(true);
					t.start();
				} catch (Throwable error) {
					runningFetches.remove(this);
					Logger.error(this, "Caught "+error);
				}
			}
		}

		public void run() {
			// Already added to runningFetches.
			// But need to make sure we are removed when we exit.
			try {
				// Do the fetch
				Fetcher f = new Fetcher(uri, blockFetchContext);
				try {
					f.realRun(new ClientMetadata(), recursionLevel, uri, 
							(!nonFullBlocksAllowed) || fetcherContext.dontEnterImplicitArchives);
				} catch (MetadataParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (FetchException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ArchiveFailureException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ArchiveRestartException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				};
			} finally {
				runningFetches.remove(this);
				synchronized(Segment.this) {
					Segment.this.notify();
				}
			}
		}

	}

	final short splitfileType;
	final FreenetURI[] dataBlocks;
	final FreenetURI[] checkBlocks;
	final BlockStatus[] dataBlockStatus;
	final BlockStatus[] checkBlockStatus;
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
	/** Error code, or -1 */
	private short fetchError;
	/** Bucket to store the data retrieved, after it has been decoded */
	private Bucket decodedData;
	/** Recently completed fetches */
	private final LinkedList recentlyCompletedFetches;
	/** Total number of successfully fetched blocks */
	private int totalFetched;
	/** Running fetches */
	private LinkedList runningFetches;
	/** Minimum retry level of any BlockStatus; this is the largest integer n such that
	 * blocksNotTried.get(n-1) is empty. Initially 0.
	 */
	private int minRetryLevel;
	/** Maximum retry level. */
	private final int maxRetryLevel;
	/** Fetch context for block fetches */
	private final FetcherContext blockFetchContext;
	/** Recursion level */
	private final int recursionLevel;
	
	/**
	 * Create a Segment.
	 * @param splitfileType The type of the splitfile.
	 * @param splitfileDataBlocks The data blocks to fetch.
	 * @param splitfileCheckBlocks The check blocks to fetch.
	 */
	public Segment(short splitfileType, FreenetURI[] splitfileDataBlocks, FreenetURI[] splitfileCheckBlocks,
			SplitFetcher fetcher, ArchiveContext actx, FetcherContext fctx, long maxTempLength, boolean useLengths, int recursionLevel) throws MetadataParseException {
		this.splitfileType = splitfileType;
		dataBlocks = splitfileDataBlocks;
		checkBlocks = splitfileCheckBlocks;
		parentFetcher = fetcher;
		archiveContext = actx;
		fetcherContext = fctx;
		maxBlockLength = maxTempLength;
		nonFullBlocksAllowed = useLengths;
		started = false;
		finished = false;
		fetchError = -1;
		decodedData = null;
		dataBlockStatus = new BlockStatus[dataBlocks.length];
		checkBlockStatus = new BlockStatus[checkBlocks.length];
		blocksNotTried = new Vector();
		maxRetryLevel = fetcherContext.maxSplitfileBlockRetries;
		Vector firstSet = new Vector(dataBlocks.length+checkBlocks.length);
		blocksNotTried.add(0, firstSet);
		for(int i=0;i<dataBlocks.length;i++) {
			dataBlockStatus[i] = new BlockStatus(dataBlocks[i]);
			firstSet.add(dataBlockStatus[i]);
		}
		for(int i=0;i<checkBlocks.length;i++) {
			checkBlockStatus[i] = new BlockStatus(checkBlocks[i]);
			firstSet.add(checkBlockStatus[i]);
		}
		recentlyCompletedFetches = new LinkedList();
		runningFetches = new LinkedList();
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			minFetched = dataBlocks.length;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			minFetched = dataBlocks.length;
		} else throw new MetadataParseException("Unknown splitfile type "+splitfileType);
		minRetryLevel = 0;
		this.recursionLevel = recursionLevel;
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
		if(fetchError != -1)
			throw new FetchException(fetchError);
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
		BucketTools.copyTo(decodedData, os, truncateLength);
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
	 * notify the SplitFetcher.
	 */
	public void start() {
		started = true;
		Thread t = new Thread(this);
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Fetch the data.
	 * Tell the SplitFetcher.
	 * Decode the data.
	 * Tell the SplitFetcher.
	 * If there is an error, tell the SplitFetcher and exit.
	 */
	public void run() {
		// Create a number of fetcher threads.
		// Wait for any thread to complete (success or failure).
		// Retry if necessary, up to N times per block.
		
		for(int i=0;i<fetcherContext.maxSplitfileThreads;i++) {
			startFetch(); // ignore return value
		}
		
		while(true) {
		
			// Now wait for any thread to complete
			synchronized(this) {
				wait(10*1000);
			}
			
			while(true) {
				BlockStatus block;
				synchronized(this) {
					block = (BlockStatus) recentlyCompletedFetches.removeFirst();
				}
				if(block == null) break;
				if(block.failed()) {
					// Retry
					int retryLevel = block.completedTries;
					if(retryLevel == maxRetryLevel) {
						// This block failed
					} else {
						Vector levelSet = (Vector) blocksNotTried.get(retryLevel);
						levelSet.add(block);
					}
				} else {
					// Succeeded
					totalFetched++;
				}
				// Either way, start a new fetch
				if(!startFetch()) {
					// Can't start a fetch
					if(runningFetches() == 0) {
						// Failed
						parentFetcher.failedNotEnoughBlocks();
						return;
					}
				}
			}
			
			if(totalFetched >= minFetched) {
				// Success! Go to next phase
				break;
			}
		}
		
		parentFetcher.gotBlocks(this);

		// Now decode
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			// TODO put the data together
		} else {
			// TODO decode via onion
		}
		
		parentFetcher.decoded(this);
		
		// TODO create healing blocks
	}

	/**
	 * Start a fetch.
	 * @return True if we started a fetch, false if there was nothing to start.
	 */
	private boolean startFetch() {
		if(minRetryLevel == maxRetryLevel) return false; // nothing to start
		// Don't need to synchronize as these are only accessed by main thread
		Vector v = (Vector) blocksNotTried.get(minRetryLevel);
		int len = v.size();
		int idx = fetcherContext.random.nextInt(len);
		BlockStatus b = (BlockStatus) v.remove(idx);
		if(v.isEmpty()) minRetryLevel++;
		b.startFetch();
	}
}
