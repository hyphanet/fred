/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;

import freenet.client.ArchiveContext;
import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.SplitfileBlock;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * A single segment within a SplitFileFetcher.
 * This in turn controls a large number of SingleFileFetcher's.
 */
public class SplitFileFetcherSegment implements GetCompletionCallback {

	private static boolean logMINOR;
	final short splitfileType;
	final FreenetURI[] dataBlocks;
	final FreenetURI[] checkBlocks;
	final ClientGetState[] dataBlockStatus;
	final ClientGetState[] checkBlockStatus;
	final MinimalSplitfileBlock[] dataBuckets;
	final MinimalSplitfileBlock[] checkBuckets;
	final int minFetched;
	final SplitFileFetcher parentFetcher;
	final ArchiveContext archiveContext;
	final FetcherContext fetcherContext;
	final long maxBlockLength;
	/** Has the segment finished processing? Irreversible. */
	private boolean finished;
	private boolean startedDecode;
	/** Bucket to store the data retrieved, after it has been decoded */
	private Bucket decodedData;
	/** Fetch context for block fetches */
	final FetcherContext blockFetchContext;
	/** Recursion level */
	final int recursionLevel;
	private FetchException failureException;
	private int fatallyFailedBlocks;
	private int failedBlocks;
	private int fetchedBlocks;
	private final FailureCodeTracker errors;
	
	public SplitFileFetcherSegment(short splitfileType, FreenetURI[] splitfileDataBlocks, FreenetURI[] splitfileCheckBlocks, SplitFileFetcher fetcher, ArchiveContext archiveContext, FetcherContext fetchContext, long maxTempLength, int recursionLevel) throws MetadataParseException, FetchException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.parentFetcher = fetcher;
		this.errors = new FailureCodeTracker(false);
		this.archiveContext = archiveContext;
		this.splitfileType = splitfileType;
		dataBlocks = splitfileDataBlocks;
		checkBlocks = splitfileCheckBlocks;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			minFetched = dataBlocks.length;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			minFetched = dataBlocks.length;
		} else throw new MetadataParseException("Unknown splitfile type"+splitfileType);
		finished = false;
		decodedData = null;
		dataBlockStatus = new ClientGetState[dataBlocks.length];
		checkBlockStatus = new ClientGetState[checkBlocks.length];
		dataBuckets = new MinimalSplitfileBlock[dataBlocks.length];
		checkBuckets = new MinimalSplitfileBlock[checkBlocks.length];
		for(int i=0;i<dataBuckets.length;i++) {
			dataBuckets[i] = new MinimalSplitfileBlock(i);
		}
		for(int i=0;i<checkBuckets.length;i++)
			checkBuckets[i] = new MinimalSplitfileBlock(i+dataBuckets.length);
		this.fetcherContext = fetchContext;
		maxBlockLength = maxTempLength;
		blockFetchContext = new FetcherContext(fetcherContext, FetcherContext.SPLITFILE_DEFAULT_BLOCK_MASK, true);
		this.recursionLevel = 0;
		if(logMINOR) Logger.minor(this, "Created "+this+" for "+parentFetcher);
		for(int i=0;i<dataBlocks.length;i++)
			if(dataBlocks[i] == null) throw new NullPointerException("Null: data block "+i);
		for(int i=0;i<checkBlocks.length;i++)
			if(checkBlocks[i] == null) throw new NullPointerException("Null: check block "+i);
	}

	public synchronized boolean isFinished() {
		return finished;
	}

	/** Throw a FetchException, if we have one. Else do nothing. */
	public synchronized void throwError() throws FetchException {
		if(failureException != null)
			throw failureException;
	}
	
	/** Decoded length? */
	public long decodedLength() {
		return decodedData.size();
	}

	/** Write the decoded segment's data to an OutputStream */
	public long writeDecodedDataTo(OutputStream os, long truncateLength) throws IOException {
		long len = decodedData.size();
		if((truncateLength >= 0) && (truncateLength < len))
			len = truncateLength;
		BucketTools.copyTo(decodedData, os, Math.min(truncateLength, decodedData.size()));
		return len;
	}

	/** How many blocks have failed due to running out of retries? */
	public synchronized int failedBlocks() {
		return failedBlocks;
	}
	
	/** How many blocks have been successfully fetched? */
	public synchronized int fetchedBlocks() {
		return fetchedBlocks;
	}

	/** How many blocks have currently running requests? */ 
	public int runningBlocks() {
		// FIXME implement or throw out
		return 0;
	}

	/** How many blocks failed permanently due to fatal errors? */
	public synchronized int fatallyFailedBlocks() {
		return fatallyFailedBlocks;
	}

	public synchronized void onSuccess(FetchResult result, ClientGetState state) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(finished) return;
		int blockNo = (int) state.getToken();
		if(blockNo < dataBlocks.length) {
			if(dataBlocks[blockNo] == null) {
				Logger.error(this, "Block already finished: "+blockNo);
				return;
			}
			dataBlocks[blockNo] = null;
			dataBuckets[blockNo].setData(result.asBucket());
		} else if(blockNo < checkBlocks.length + dataBlocks.length) {
			blockNo -= dataBlocks.length;
			if(checkBlocks[blockNo] == null) {
				Logger.error(this, "Check block already finished: "+blockNo);
				return;
			}
			checkBlocks[blockNo] = null;
			checkBuckets[blockNo].setData(result.asBucket());
		} else
			Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
		fetchedBlocks++;
		if(fetchedBlocks >= minFetched)
			startDecode();
	}

	private void startDecode() {
		synchronized(this) {
			if(startedDecode) return;
			startedDecode = true;
		}
		for(int i=0;i<dataBlockStatus.length;i++) {
			ClientGetState f = dataBlockStatus[i];
			if(f != null) f.cancel();
		}
		for(int i=0;i<checkBlockStatus.length;i++) {
			ClientGetState f = checkBlockStatus[i];
			if(f != null) f.cancel();
		}
		Runnable r = new Decoder();
		Thread t = new Thread(r, "Decoder for "+this);
		t.setDaemon(true);
		t.start();
	}
	
	class Decoder implements Runnable {

		public void run() {
			
			// Now decode
			if(logMINOR) Logger.minor(this, "Decoding "+this);
			
			boolean[] dataBlocksSucceeded = new boolean[dataBuckets.length];
			boolean[] checkBlocksSucceeded = new boolean[checkBuckets.length];
			for(int i=0;i<dataBuckets.length;i++)
				dataBlocksSucceeded[i] = dataBuckets[i].data != null;
			for(int i=0;i<checkBuckets.length;i++)
				checkBlocksSucceeded[i] = checkBuckets[i].data != null;
			
			FECCodec codec = FECCodec.getCodec(splitfileType, dataBlocks.length, checkBlocks.length);
			try {
				if(splitfileType != Metadata.SPLITFILE_NONREDUNDANT) {
					codec.decode(dataBuckets, checkBuckets, CHKBlock.DATA_LENGTH, fetcherContext.bucketFactory);
					// Now have all the data blocks (not necessarily all the check blocks)
				}
				
				decodedData = fetcherContext.bucketFactory.makeBucket(-1);
				if(logMINOR) Logger.minor(this, "Copying data from data blocks");
				OutputStream os = decodedData.getOutputStream();
				for(int i=0;i<dataBlockStatus.length;i++) {
					SplitfileBlock status = dataBuckets[i];
					Bucket data = status.getData();
					BucketTools.copyTo(data, os, Long.MAX_VALUE);
				}
				if(logMINOR) Logger.minor(this, "Copied data");
				os.close();
				// Must set finished BEFORE calling parentFetcher.
				// Otherwise a race is possible that might result in it not seeing our finishing.
				finished = true;
				parentFetcher.segmentFinished(SplitFileFetcherSegment.this);
			} catch (IOException e) {
				Logger.normal(this, "Caught bucket error?: "+e, e);
				finished = true;
				failureException = new FetchException(FetchException.BUCKET_ERROR);
				parentFetcher.segmentFinished(SplitFileFetcherSegment.this);
				return;
			}
			
			// Now heal
			
			/** Splitfile healing:
			 * Any block which we have tried and failed to download should be 
			 * reconstructed and reinserted.
			 */
			
			// Encode any check blocks we don't have
			if(codec != null) {
				try {
					codec.encode(dataBuckets, checkBuckets, 32768, fetcherContext.bucketFactory);
				} catch (IOException e) {
					Logger.error(this, "Bucket error while healing: "+e, e);
				}
			
				// Now insert *ALL* blocks on which we had at least one failure, and didn't eventually succeed
				for(int i=0;i<dataBlockStatus.length;i++) {
					boolean heal = false;
					if(!dataBlocksSucceeded[i]) {
						SimpleSingleFileFetcher sf = (SimpleSingleFileFetcher) dataBlockStatus[i];
						if(sf.getRetryCount() > 0)
							heal = true;
					}
					if(heal) {
						queueHeal(dataBuckets[i].getData());
					} else {
						dataBuckets[i].data.free();
						dataBuckets[i].data = null;
					}
					dataBuckets[i] = null;
					dataBlockStatus[i] = null;
					dataBlocks[i] = null;
				}
				for(int i=0;i<checkBlockStatus.length;i++) {
					boolean heal = false;
					if(!checkBlocksSucceeded[i]) {
						SimpleSingleFileFetcher sf = (SimpleSingleFileFetcher) checkBlockStatus[i];
						if(sf.getRetryCount() > 0)
							heal = true;
					}
					if(heal) {
						queueHeal(checkBuckets[i].getData());
					} else {
						checkBuckets[i].data.free();
					}
					checkBuckets[i] = null;
					checkBlockStatus[i] = null;
					checkBlocks[i] = null;
				}
			}
		}

	}

	private void queueHeal(Bucket data) {
		if(logMINOR) Logger.minor(this, "Queueing healing insert");
		fetcherContext.healingQueue.queue(data);
	}
	
	/** This is after any retries and therefore is either out-of-retries or fatal */
	public synchronized void onFailure(FetchException e, ClientGetState state) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		int blockNo = (int) state.getToken();
		if(blockNo < dataBlocks.length) {
			if(dataBlocks[blockNo] == null) {
				Logger.error(this, "Block already finished: "+blockNo);
				return;
			}
			dataBlocks[blockNo] = null;
		} else if(blockNo < checkBlocks.length + dataBlocks.length) {
			blockNo -= dataBlocks.length;
			if(checkBlocks[blockNo] == null) {
				Logger.error(this, "Check block already finished: "+blockNo);
				return;
			}
			checkBlocks[blockNo] = null;
		} else
			Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
		// :(
		if(logMINOR) Logger.minor(this, "Permanently failed block: "+state+" on "+this);
		if(e.isFatal())
			fatallyFailedBlocks++;
		else
			failedBlocks++;
		// FIXME this may not be accurate across all the retries?
		if(e.errorCodes != null)
			errors.merge(e.errorCodes);
		else
			errors.inc(new Integer(e.mode), state == null ? 1 : ((SingleFileFetcher)state).getRetryCount());
		if(failedBlocks + fatallyFailedBlocks > (dataBlocks.length + checkBlocks.length - minFetched)) {
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors));
		}
	}

	private void fail(FetchException e) {
		synchronized(this) {
			if(finished) return;
			finished = true;
			this.failureException = e;
			if(startedDecode) {
				Logger.error(this, "Failing with "+e+" but already started decode", e);
				return;
			}
			for(int i=0;i<dataBlockStatus.length;i++) {
				ClientGetState f = dataBlockStatus[i];
				if(f != null)
					f.cancel();
				MinimalSplitfileBlock b = dataBuckets[i];
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				dataBuckets[i] = null;
			}
			for(int i=0;i<checkBlockStatus.length;i++) {
				ClientGetState f = checkBlockStatus[i];
				if(f != null)
					f.cancel();
				MinimalSplitfileBlock b = checkBuckets[i];
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				checkBuckets[i] = null;
			}
		}
		parentFetcher.segmentFinished(this);
	}

	public void schedule() {
		try {
			for(int i=0;i<dataBlocks.length;i++) {
				if(dataBlocks[i] == null) {
					// Already fetched?
					continue;
				}
				// FIXME maybe within a non-FECced splitfile at least?
				if(dataBlocks[i].getKeyType().equals("USK")) {
					fail(new FetchException(FetchException.INVALID_METADATA, "Cannot have USKs within a splitfile!"));
					return;
				}
				if(dataBlockStatus[i] != null) {
					Logger.error(this, "Scheduling twice? dataBlockStatus["+i+"] = "+dataBlockStatus[i]);
				} else {
					dataBlockStatus[i] =
						(ClientGetState) SingleFileFetcher.create(parentFetcher.parent, this, null, dataBlocks[i], blockFetchContext, archiveContext, blockFetchContext.maxNonSplitfileRetries, recursionLevel, true, i, true, null, false);
				}
			}
			for(int i=0;i<checkBlocks.length;i++) {
				if(checkBlocks[i] == null) {
					// Already fetched?
					continue;
				}
				// FIXME maybe within a non-FECced splitfile at least?
				if(checkBlocks[i].getKeyType().equals("USK")) {
					fail(new FetchException(FetchException.INVALID_METADATA, "Cannot have USKs within a splitfile!"));
					return;
				}
				if(checkBlockStatus[i] != null) {
					Logger.error(this, "Scheduling twice? dataBlockStatus["+i+"] = "+checkBlockStatus[i]);
				} else checkBlockStatus[i] =
					(ClientGetState) SingleFileFetcher.create(parentFetcher.parent, this, null, checkBlocks[i], blockFetchContext, archiveContext, blockFetchContext.maxNonSplitfileRetries, recursionLevel, true, dataBlocks.length+i, false, null, false);
			}
			for(int i=0;i<dataBlocks.length;i++) {
				if(dataBlockStatus[i] != null)
					dataBlockStatus[i].schedule();
			}
			for(int i=0;i<checkBlocks.length;i++)
				if(checkBlockStatus[i] != null)
					checkBlockStatus[i].schedule();
		} catch (MalformedURLException e) {
			// Invalidates the whole splitfile
			fail(new FetchException(FetchException.INVALID_URI, "Invalid URI in splitfile: "+e));
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t+" scheduling "+this, t);
			fail(new FetchException(FetchException.INTERNAL_ERROR, t));
		}
	}

	public void cancel() {
		fail(new FetchException(FetchException.CANCELLED));
	}

	public void onBlockSetFinished(ClientGetState state) {
		// Ignore; irrelevant
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore
	}

}
