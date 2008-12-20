/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

import freenet.client.ArchiveContext;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FailureCodeTracker;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.SplitfileBlock;
import freenet.client.FECCodec.StandardOnionFECCodecEncoderCallback;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.node.RequestScheduler;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * A single segment within a SplitFileFetcher.
 * This in turn controls a large number of SplitFileFetcherSubSegment's, which are registered on the ClientRequestScheduler.
 */
public class SplitFileFetcherSegment implements StandardOnionFECCodecEncoderCallback {

	private static volatile boolean logMINOR;
	final short splitfileType;
	final ClientCHK[] dataKeys;
	final ClientCHK[] checkKeys;
	final MinimalSplitfileBlock[] dataBuckets;
	final MinimalSplitfileBlock[] checkBuckets;
	final long[] dataCooldownTimes;
	final long[] checkCooldownTimes;
	final int[] dataRetries;
	final int[] checkRetries;
	final Vector subSegments;
	final int minFetched;
	final SplitFileFetcher parentFetcher;
	final ArchiveContext archiveContext;
	final FetchContext fetchContext;
	final long maxBlockLength;
	/** Has the segment finished processing? Irreversible. */
	private volatile boolean finished;
	private boolean startedDecode;
	/** Bucket to store the data retrieved, after it has been decoded */
	private Bucket decodedData;
	/** Fetch context for block fetches */
	final FetchContext blockFetchContext;
	/** Recursion level */
	final int recursionLevel;
	private FetchException failureException;
	/**
	 * If true, the last data block has bad padding and cannot be involved in FEC decoding.
	 */
	private final boolean ignoreLastDataBlock;
	private int fatallyFailedBlocks;
	private int failedBlocks;
	/**
	 * The number of blocks fetched. If ignoreLastDataBlock is set, fetchedBlocks does not
	 * include the last data block.
	 */
	private int fetchedBlocks;
	/**
	 * The number of data blocks (NOT check blocks) fetched. On a small splitfile, sometimes
	 * we will manage to fetch all the data blocks. If so, we can complete the segment, even
	 * if we can't do a FEC decode because of ignoreLastDataBlock.
	 */
	private int fetchedDataBlocks;
	final FailureCodeTracker errors;
	private boolean finishing;
	private boolean scheduled;
	
	private FECCodec codec;
	
	public SplitFileFetcherSegment(short splitfileType, ClientCHK[] splitfileDataKeys, ClientCHK[] splitfileCheckKeys, SplitFileFetcher fetcher, ArchiveContext archiveContext, FetchContext fetchContext, long maxTempLength, int recursionLevel, boolean ignoreLastDataBlock) throws MetadataParseException, FetchException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.parentFetcher = fetcher;
		this.ignoreLastDataBlock = ignoreLastDataBlock;
		this.errors = new FailureCodeTracker(false);
		this.archiveContext = archiveContext;
		this.splitfileType = splitfileType;
		dataKeys = splitfileDataKeys;
		checkKeys = splitfileCheckKeys;
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
			minFetched = dataKeys.length;
		} else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			minFetched = dataKeys.length;
		} else throw new MetadataParseException("Unknown splitfile type"+splitfileType);
		finished = false;
		decodedData = null;
		dataBuckets = new MinimalSplitfileBlock[dataKeys.length];
		checkBuckets = new MinimalSplitfileBlock[checkKeys.length];
		for(int i=0;i<dataBuckets.length;i++) {
			dataBuckets[i] = new MinimalSplitfileBlock(i);
		}
		for(int i=0;i<checkBuckets.length;i++)
			checkBuckets[i] = new MinimalSplitfileBlock(i+dataBuckets.length);
		dataRetries = new int[dataKeys.length];
		checkRetries = new int[checkKeys.length];
		dataCooldownTimes = new long[dataKeys.length];
		checkCooldownTimes = new long[checkKeys.length];
		subSegments = new Vector();
		this.fetchContext = fetchContext;
		maxBlockLength = maxTempLength;
		blockFetchContext = new FetchContext(fetchContext, FetchContext.SPLITFILE_DEFAULT_BLOCK_MASK, true);
		this.recursionLevel = 0;
		if(logMINOR) Logger.minor(this, "Created "+this+" for "+parentFetcher+" : "+dataRetries.length+" data blocks "+checkRetries.length+" check blocks");
		for(int i=0;i<dataKeys.length;i++)
			if(dataKeys[i] == null) throw new NullPointerException("Null: data block "+i);
		for(int i=0;i<checkKeys.length;i++)
			if(checkKeys[i] == null) throw new NullPointerException("Null: check block "+i);
	}

	public synchronized boolean isFinished() {
		return finished || parentFetcher.parent == null || parentFetcher.parent.isCancelled();
	}

	public synchronized boolean isFinishing() {
		return isFinished() || finishing;
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

	/** How many blocks failed permanently due to fatal errors? */
	public synchronized int fatallyFailedBlocks() {
		return fatallyFailedBlocks;
	}

	public void onSuccess(Bucket data, int blockNo, SplitFileFetcherSubSegment seg, ClientKeyBlock block) {
		if(data == null) throw new NullPointerException();
		boolean decodeNow = false;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Fetched block "+blockNo+" on "+seg+" data="+dataBuckets.length+" check="+checkBuckets.length);
		if (parentFetcher.parent != null && parentFetcher.parent instanceof ClientGetter)
			((ClientGetter)parentFetcher.parent).addKeyToBinaryBlob(block);
		// No need to unregister key, because it will be cleared in tripPendingKey().
		boolean dontNotify;
		boolean wasDataBlock = false;
		boolean allFailed = false;
		synchronized(this) {
			if(blockNo < dataKeys.length) {
				wasDataBlock = true;
				if(dataKeys[blockNo] == null) {
					if(!startedDecode) Logger.error(this, "Block already finished: "+blockNo);
					data.free();
					return;
				}
				dataRetries[blockNo] = 0; // Prevent healing of successfully fetched block.
				dataKeys[blockNo] = null;
				dataBuckets[blockNo].setData(data);
			} else if(blockNo < checkKeys.length + dataKeys.length) {
				blockNo -= dataKeys.length;
				if(checkKeys[blockNo] == null) {
					if(!startedDecode) Logger.error(this, "Check block already finished: "+blockNo);
					data.free();
					return;
				}
				checkRetries[blockNo] = 0; // Prevent healing of successfully fetched block.
				checkKeys[blockNo] = null;
				checkBuckets[blockNo].setData(data);
			} else
				Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
			if(startedDecode) {
				return;
			} else {
				// Don't count the last data block, since we can't use it in FEC decoding.
				if(!(ignoreLastDataBlock && blockNo == dataKeys.length - 1))
					fetchedBlocks++;
				else
					// This block is not going to be fetched, and because of the insertion format. 
					// Thus it is a fatal failure. We need to track it, because it is quite possible
					// to fetch the last block, not complete because it's the last block, and hang.
					fatallyFailedBlocks++;
				// However, if we manage to get EVERY data block (common on a small splitfile),
				// we don't need to FEC decode.
				if(wasDataBlock)
					fetchedDataBlocks++;
				if(logMINOR) Logger.minor(this, "Fetched "+fetchedBlocks+" blocks in onSuccess("+blockNo+")");
				boolean haveDataBlocks = fetchedDataBlocks == dataKeys.length;
				decodeNow = (fetchedBlocks >= minFetched || haveDataBlocks);
				if(decodeNow) {
					startedDecode = true;
					finishing = true;
				} else {
					// Avoid hanging when we have one n-1 check blocks, we succeed on the last data block,
					// we don't have the other data blocks, and we have nothing else fetching.
					allFailed = failedBlocks + fatallyFailedBlocks > (dataKeys.length + checkKeys.length - minFetched);
				}
			}
			dontNotify = !scheduled;
		}
		parentFetcher.parent.completedBlock(dontNotify);
		seg.possiblyRemoveFromParent();
		if(decodeNow) {
			removeSubSegments();
			decode();
		} else if(allFailed) {
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors));
		}
	}

	public void decode() {
		// Now decode
		if(logMINOR) Logger.minor(this, "Decoding "+SplitFileFetcherSegment.this);

		codec = FECCodec.getCodec(splitfileType, dataKeys.length, checkKeys.length, blockFetchContext.executor);
		
		if(splitfileType != Metadata.SPLITFILE_NONREDUNDANT) {
			codec.addToQueue(new FECJob(codec, dataBuckets, checkBuckets, CHKBlock.DATA_LENGTH, fetchContext.bucketFactory, this, true));
			// Now have all the data blocks (not necessarily all the check blocks)
		}
	}
	
	public void onDecodedSegment() {
		try {
			if(isCollectingBinaryBlob()) {
				for(int i=0;i<dataBuckets.length;i++) {
					Bucket data = dataBuckets[i].getData();
					if(data == null) 
						throw new NullPointerException("Data bucket "+i+" of "+dataBuckets.length+" is null");
					try {
						maybeAddToBinaryBlob(data, i, false);
					} catch (FetchException e) {
						fail(e);
						return;
					}
				}
			}
			decodedData = fetchContext.bucketFactory.makeBucket(maxBlockLength * dataBuckets.length);
			if(logMINOR) Logger.minor(this, "Copying data from data blocks");
			OutputStream os = decodedData.getOutputStream();
			long osSize = 0;
			for(int i=0;i<dataBuckets.length;i++) {
				SplitfileBlock status = dataBuckets[i];
				Bucket data = status.getData();
				if(data == null) 
					throw new NullPointerException("Data bucket "+i+" of "+dataBuckets.length+" is null");
				long copied = BucketTools.copyTo(data, os, Long.MAX_VALUE);
				osSize += copied;
				if(i != dataBuckets.length-1 && copied != 32768)
					Logger.error(this, "Copied only "+copied+" bytes from "+data+" (bucket "+i+")");
				if(logMINOR) Logger.minor(this, "Copied "+copied+" bytes from bucket "+i);
			}
			if(logMINOR) Logger.minor(this, "Copied data ("+osSize+")");
			os.close();
			// Must set finished BEFORE calling parentFetcher.
			// Otherwise a race is possible that might result in it not seeing our finishing.
			finished = true;
			if(codec == null || !isCollectingBinaryBlob())
				parentFetcher.segmentFinished(SplitFileFetcherSegment.this);
		} catch (IOException e) {
			Logger.normal(this, "Caught bucket error?: "+e, e);
			synchronized(this) {
				finished = true;
				failureException = new FetchException(FetchException.BUCKET_ERROR);
			}
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
			codec.addToQueue(new FECJob(codec, dataBuckets, checkBuckets, CHKBlock.DATA_LENGTH, fetchContext.bucketFactory, this, false));
		}
	}

	public void onEncodedSegment() {
		synchronized(this) {
			// Now insert *ALL* blocks on which we had at least one failure, and didn't eventually succeed
			for(int i=0;i<dataBuckets.length;i++) {
				boolean heal = false;
				Bucket data = dataBuckets[i].getData();
				if(dataRetries[i] > 0)
					heal = true;
				if(heal) {
					queueHeal(data);
				} else {
					dataBuckets[i].data.free();
					dataBuckets[i].data = null;
				}
				dataBuckets[i] = null;
				dataKeys[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				boolean heal = false;
				Bucket data = checkBuckets[i].getData();
				try {
					maybeAddToBinaryBlob(data, i, true);
				} catch (FetchException e) {
					fail(e);
					return;
				}
				if(checkRetries[i] > 0)
					heal = true;
				if(heal) {
					queueHeal(data);
				} else {
					checkBuckets[i].data.free();
				}
				checkBuckets[i] = null;
				checkKeys[i] = null;
			}
		}
		// Defer the completion until we have generated healing blocks if we are collecting binary blobs.
		if(isCollectingBinaryBlob())
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this);
	}

	boolean isCollectingBinaryBlob() {
		if (parentFetcher.parent != null && parentFetcher.parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parentFetcher.parent);
			return getter.collectingBinaryBlob();
		} else return false;
	}
	
	private void maybeAddToBinaryBlob(Bucket data, int i, boolean check) throws FetchException {
		if (parentFetcher.parent != null && parentFetcher.parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parentFetcher.parent);
			if(getter.collectingBinaryBlob()) {
				try {
					ClientCHKBlock block =
						ClientCHKBlock.encode(data, false, true, (short)-1, data.size());
					getter.addKeyToBinaryBlob(block);
				} catch (CHKEncodeException e) {
					Logger.error(this, "Failed to encode (collecting binary blob) "+(check?"check":"data")+" block "+i+": "+e, e);
					throw new FetchException(FetchException.INTERNAL_ERROR, "Failed to encode for binary blob: "+e);
				} catch (IOException e) {
					throw new FetchException(FetchException.BUCKET_ERROR, "Failed to encode for binary blob: "+e);
				}
			}
		}
	}

	private void queueHeal(Bucket data) {
		if(logMINOR) Logger.minor(this, "Queueing healing insert for "+data+" on "+this);
		fetchContext.healingQueue.queue(data);
	}
	
	/** This is after any retries and therefore is either out-of-retries or fatal */
	public void onFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Permanently failed block: "+blockNo+" on "+this+" : "+e, e);
		boolean allFailed;
		// Since we can't keep the key, we need to unregister for it at this point to avoid a memory leak
		NodeCHK key = getBlockNodeKey(blockNo);
		if(key != null) seg.unregisterKey(key);
		synchronized(this) {
			if(isFinishing()) return; // this failure is now irrelevant, and cleanup will occur on the decoder thread
			if(blockNo < dataKeys.length) {
				if(dataKeys[blockNo] == null) {
					Logger.error(this, "Block already finished: "+blockNo);
					return;
				}
				dataKeys[blockNo] = null;
			} else if(blockNo < checkKeys.length + dataKeys.length) {
				if(checkKeys[blockNo-dataKeys.length] == null) {
					Logger.error(this, "Check block already finished: "+blockNo);
					return;
				}
				checkKeys[blockNo-dataKeys.length] = null;
			} else
				Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
			// :(
			if(e.isFatal()) {
				fatallyFailedBlocks++;
				if (parentFetcher.parent != null)
				parentFetcher.parent.fatallyFailedBlock();
			} else {
				failedBlocks++;
				if (parentFetcher.parent != null)
				parentFetcher.parent.failedBlock();
			}
			// Once it is no longer possible to have a successful fetch, fail...
			allFailed = failedBlocks + fatallyFailedBlocks > (dataKeys.length + checkKeys.length - minFetched);
		}
		if(allFailed)
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors));
		else
			seg.possiblyRemoveFromParent();
	}
	
	/** A request has failed non-fatally, so the block may be retried */
	public void onNonFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, RequestScheduler sched) {
		int tries;
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		boolean failed = false;
		boolean cooldown = false;
		ClientCHK key;
		SplitFileFetcherSubSegment sub = null;
		synchronized(this) {
			if(isFinished()) return;
			if(blockNo < dataKeys.length) {
				key = dataKeys[blockNo];
				tries = ++dataRetries[blockNo];
				if(tries > maxTries && maxTries >= 0) failed = true;
				else {
					sub = getSubSegment(tries);
					if(tries % ClientRequestScheduler.COOLDOWN_RETRIES == 0) {
						long now = System.currentTimeMillis();
						if(dataCooldownTimes[blockNo] > now)
							Logger.error(this, "Already on the cooldown queue! for "+this+" data block no "+blockNo, new Exception("error"));
						else
							dataCooldownTimes[blockNo] = sched.queueCooldown(key, sub);
						cooldown = true;
					}
				}
			} else {
				int checkNo = blockNo - dataKeys.length;
				key = checkKeys[checkNo];
				tries = ++checkRetries[checkNo];
				if(tries > maxTries && maxTries >= 0) failed = true;
				else {
					sub = getSubSegment(tries);
					if(tries % ClientRequestScheduler.COOLDOWN_RETRIES == 0) {
						long now = System.currentTimeMillis();
						if(checkCooldownTimes[checkNo] > now)
							Logger.error(this, "Already on the cooldown queue! for "+this+" check block no "+blockNo, new Exception("error"));
						else
							checkCooldownTimes[checkNo] = sched.queueCooldown(key, sub);
						cooldown = true;
					}
				}
			}
		}
		if(failed) {
			onFatalFailure(e, blockNo, seg);
			if(logMINOR)
				Logger.minor(this, "Not retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries);
			return;
		}
		if(cooldown) {
			// Register to the next sub-segment before removing from the old one.
			sub.getScheduler().addPendingKey(key, sub);
			seg.unregisterKey(key.getNodeKey());
		} else {
			// If we are here we are going to retry
			// Unregister from the old sub-segment before registering on the new.
			seg.unregisterKey(key.getNodeKey());
			if(logMINOR)
				Logger.minor(this, "Retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
			sub.add(blockNo, false);
		}
	}
	
	private SplitFileFetcherSubSegment getSubSegment(int retryCount) {
		SplitFileFetcherSubSegment sub;
		synchronized(this) {
			for(int i=0;i<subSegments.size();i++) {
				sub = (SplitFileFetcherSubSegment) subSegments.get(i);
				if(sub.retryCount == retryCount) return sub;
			}
			sub = new SplitFileFetcherSubSegment(this, retryCount);
			subSegments.add(sub);
		}
		return sub;
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
			for(int i=0;i<dataBuckets.length;i++) {
				MinimalSplitfileBlock b = dataBuckets[i];
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				dataBuckets[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				MinimalSplitfileBlock b = checkBuckets[i];
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				checkBuckets[i] = null;
			}
		}
		removeSubSegments();
		parentFetcher.segmentFinished(this);
	}

	public void schedule() {
		try {
			SplitFileFetcherSubSegment seg = getSubSegment(0);
			for(int i=0;i<dataRetries.length+checkRetries.length;i++) {
				seg.add(i, true);
			}
			
			seg.schedule();
			synchronized(this) {
				scheduled = true;
			}
			if (parentFetcher.parent != null)
				parentFetcher.parent.notifyClients();
			if(logMINOR)
				Logger.minor(this, "scheduling "+seg+" : "+seg.blockNums);
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

	public synchronized ClientCHK getBlockKey(int blockNum) {
		if(blockNum < 0) return null;
		else if(blockNum < dataKeys.length)
			return dataKeys[blockNum];
		else if(blockNum < dataKeys.length + checkKeys.length)
			return checkKeys[blockNum - dataKeys.length];
		else return null;
	}
	
	public NodeCHK getBlockNodeKey(int blockNum) {
		ClientCHK key = getBlockKey(blockNum);
		if(key != null) return key.getNodeCHK();
		else return null;
	}

	/**
	 * Double-check whether we need to remove a subsegment, and if so, remove it.
	 * We need to do the check because there is no point removing the subsegment until all
	 * its running requests have been removed (since request data structures will refer to it
	 * anyway), and all the requests on the cooldown queue for it have been removed. In either
	 * case we get duplicated structures in memory.
	 * @return True if we removed the subsegment.
	 */
	public synchronized boolean maybeRemoveSeg(SplitFileFetcherSubSegment segment) {
		int retryCount = segment.retryCount;
		boolean dontRemove = true;
		for(int i=0;i<dataRetries.length;i++)
			if(dataRetries[i] == retryCount) {
				dontRemove = false;
				break;
			}
		for(int i=0;i<checkRetries.length;i++)
			if(checkRetries[i] == retryCount) {
				dontRemove = false;
				break;
			}
		if(isFinishing()) dontRemove = false;
		if(dontRemove) return false;
		if(logMINOR)
			Logger.minor(this, "Removing sub segment: "+segment+" for retry count "+retryCount);
		for(int i=0;i<subSegments.size();i++) {
			if(segment.equals(subSegments.get(i))) {
				subSegments.remove(i);
				i--;
			}
		}
		return true;
	}

	private void removeSubSegments() {
		SplitFileFetcherSubSegment[] deadSegs;
		synchronized(this) {
			deadSegs = (SplitFileFetcherSubSegment[]) subSegments.toArray(new SplitFileFetcherSubSegment[subSegments.size()]);
			subSegments.clear();
		}
		for(int i=0;i<deadSegs.length;i++) {
			deadSegs[i].kill();
		}
	}

	public synchronized long getCooldownWakeup(int blockNum) {
		if(blockNum < dataKeys.length)
			return dataCooldownTimes[blockNum];
		else
			return checkCooldownTimes[blockNum - dataKeys.length];
	}

	public void requeueAfterCooldown(Key key, long time) {
		Vector v = null;
		boolean notFound = true;
		synchronized(this) {
		if(isFinishing()) return;
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		for(int i=0;i<dataKeys.length;i++) {
			if(dataKeys[i] == null) continue;
			if(dataKeys[i].getNodeKey().equals(key)) {
				if(dataCooldownTimes[i] > time) {
					if(logMINOR)
						Logger.minor(this, "Not retrying after cooldown for data block "+i+"as deadline has not passed yet on "+this);
					return;
				}
				int tries = dataRetries[i];
				SplitFileFetcherSubSegment sub = getSubSegment(tries);
				if(logMINOR)
					Logger.minor(this, "Retrying after cooldown on "+this+": data block "+i+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
				if(v == null) v = new Vector();
				sub.add(i, true);
				if(!v.contains(sub)) v.add(sub);
				notFound = false;
			}
		}
		for(int i=0;i<checkKeys.length;i++) {
			if(checkKeys[i] == null) continue;
			if(checkKeys[i].getNodeKey().equals(key)) {
				if(checkCooldownTimes[i] > time) {
					if(logMINOR)
						Logger.minor(this, "Not retrying after cooldown for data block "+i+" as deadline has not passed yet on "+this);
					return;
				}
				int tries = checkRetries[i];
				SplitFileFetcherSubSegment sub = getSubSegment(tries);
				if(logMINOR)
					Logger.minor(this, "Retrying after cooldown on "+this+": check block "+i+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
				if(v == null) v = new Vector();
				sub.add(i+dataKeys.length, true);
				if(!v.contains(sub)) v.add(sub);
				notFound = false;
			}
		}
		}
		if(notFound) {
			Logger.error(this, "requeueAfterCooldown: Key not found!: "+key+" on "+this);
		}
		if(v != null) {
			for(int i=0;i<v.size();i++) {
				((SplitFileFetcherSubSegment) v.get(i)).schedule();
			}
		}
	}

	public synchronized long getCooldownWakeupByKey(Key key) {
		for(int i=0;i<dataKeys.length;i++) {
			if(dataKeys[i] == null) continue;
			if(dataKeys[i].getNodeKey().equals(key)) {
				return dataCooldownTimes[i];
			}
		}
		for(int i=0;i<checkKeys.length;i++) {
			if(checkKeys[i] == null) continue;
			if(checkKeys[i].getNodeKey().equals(key)) {
				return checkCooldownTimes[i];
			}
		}
		return -1;
	}

	public synchronized int getBlockNumber(Key key) {
		for(int i=0;i<dataKeys.length;i++)
			if(dataKeys[i] != null && dataKeys[i].getNodeKey().equals(key)) return i;
		for(int i=0;i<checkKeys.length;i++)
			if(checkKeys[i] != null && checkKeys[i].getNodeKey().equals(key)) return dataKeys.length+i;
		return -1;
	}

	public synchronized Integer[] getKeyNumbersAtRetryLevel(int retryCount) {
		Vector v = new Vector();
		for(int i=0;i<dataRetries.length;i++) {
			if(dataKeys[i] == null) continue;
			if(dataRetries[i] == retryCount)
				v.add(new Integer(i));
		}
		for(int i=0;i<checkRetries.length;i++) {
			if(checkKeys[i] == null) continue;
			if(checkRetries[i] == retryCount)
				v.add(new Integer(i+dataKeys.length));
		}
		return (Integer[]) v.toArray(new Integer[v.size()]);
	}

	public synchronized void resetCooldownTimes(Integer[] blockNums) {
		for(int i=0;i<blockNums.length;i++) {
			int blockNo = blockNums[i].intValue();
			if(blockNo < dataCooldownTimes.length)
				dataCooldownTimes[blockNo] = -1;
			else
				checkCooldownTimes[blockNo - dataCooldownTimes.length] = -1;
		}
	}

	public void freeDecodedData() {
		decodedData.free();
		decodedData = null;
	}
}
