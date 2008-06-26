/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.FECCallback;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FECQueue;
import freenet.client.FailureCodeTracker;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.SplitfileBlock;
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
public class SplitFileFetcherSegment implements FECCallback {

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
	final ClientRequester parent;
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
	private int fatallyFailedBlocks;
	private int failedBlocks;
	private int fetchedBlocks;
	final FailureCodeTracker errors;
	private boolean finishing;
	private boolean scheduled;
	private final boolean persistent;
	
	private FECCodec codec;
	
	public SplitFileFetcherSegment(short splitfileType, ClientCHK[] splitfileDataKeys, ClientCHK[] splitfileCheckKeys, SplitFileFetcher fetcher, ArchiveContext archiveContext, FetchContext fetchContext, long maxTempLength, int recursionLevel, ClientRequester requester) throws MetadataParseException, FetchException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.persistent = fetcher.persistent;
		this.parentFetcher = fetcher;
		this.errors = new FailureCodeTracker(false);
		this.archiveContext = archiveContext;
		this.splitfileType = splitfileType;
		this.parent = requester;
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
		return finished || parentFetcher.parent.isCancelled();
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

	public void onSuccess(Bucket data, int blockNo, SplitFileFetcherSubSegment seg, ClientKeyBlock block, ObjectContainer container, RequestScheduler sched) {
		if(persistent)
			container.activate(this, 1);
		boolean decodeNow = false;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Fetched block "+blockNo+" on "+seg);
		if(parentFetcher.parent instanceof ClientGetter)
			((ClientGetter)parentFetcher.parent).addKeyToBinaryBlob(block, container, sched.getContext());
		// No need to unregister key, because it will be cleared in tripPendingKey().
		boolean dontNotify;
		synchronized(this) {
			if(blockNo < dataKeys.length) {
				if(dataKeys[blockNo] == null) {
					if(!startedDecode) Logger.error(this, "Block already finished: "+blockNo);
					data.free();
					return;
				}
				dataRetries[blockNo] = 0; // Prevent healing of successfully fetched block.
				dataKeys[blockNo] = null;
				if(persistent)
					container.activate(dataBuckets[blockNo], 1);
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
				if(persistent)
					container.activate(checkBuckets[blockNo], 1);
				checkBuckets[blockNo].setData(data);
			} else
				Logger.error(this, "Unrecognized block number: "+blockNo, new Exception("error"));
			if(startedDecode) {
				return;
			} else {
				fetchedBlocks++;
				if(logMINOR) Logger.minor(this, "Fetched "+fetchedBlocks+" blocks in onSuccess("+blockNo+")");
				decodeNow = (fetchedBlocks >= minFetched);
				if(decodeNow) {
					startedDecode = true;
					finishing = true;
				}
			}
			dontNotify = !scheduled;
		}
		if(persistent)
			container.set(this);
		parentFetcher.parent.completedBlock(dontNotify, container, sched.getContext());
		seg.possiblyRemoveFromParent(container);
		if(decodeNow) {
			removeSubSegments(container);
			decode(container, sched.getContext(), sched);
		}
	}

	public void decode(ObjectContainer container, ClientContext context, RequestScheduler sched) {
		if(persistent)
			container.activate(this, 1);
		// Now decode
		if(logMINOR) Logger.minor(this, "Decoding "+SplitFileFetcherSegment.this);

		codec = FECCodec.getCodec(splitfileType, dataKeys.length, checkKeys.length, sched.getContext().mainExecutor);
		if(persistent)
			container.set(this);
		
		if(splitfileType != Metadata.SPLITFILE_NONREDUNDANT) {
			FECQueue queue = sched.getFECQueue();
			codec.addToQueue(new FECJob(codec, queue, dataBuckets, checkBuckets, CHKBlock.DATA_LENGTH, context.getBucketFactory(parentFetcher.parent.persistent()), this, true, parentFetcher.parent.getPriorityClass(), parentFetcher.parent.persistent()), 
					queue, container);
			// Now have all the data blocks (not necessarily all the check blocks)
		}
	}
	
	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets2, Bucket[] checkBuckets2, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent)
			container.activate(this, 1);
		// Because we use SplitfileBlock, we DON'T have to copy here.
		// See FECCallback comments for explanation.
		try {
			if(isCollectingBinaryBlob()) {
				for(int i=0;i<dataBuckets.length;i++) {
					Bucket data = dataBlockStatus[i].getData();
					try {
						maybeAddToBinaryBlob(data, i, false, container, context);
					} catch (FetchException e) {
						fail(e, container, context);
						return;
					}
				}
			}
			decodedData = context.getBucketFactory(parentFetcher.parent.persistent()).makeBucket(-1);
			if(logMINOR) Logger.minor(this, "Copying data from data blocks");
			OutputStream os = decodedData.getOutputStream();
			for(int i=0;i<dataBuckets.length;i++) {
				SplitfileBlock status = dataBuckets[i];
				if(persistent) container.activate(status, 1);
				Bucket data = status.getData();
				if(persistent) container.activate(data, 1);
				BucketTools.copyTo(data, os, Long.MAX_VALUE);
			}
			if(logMINOR) Logger.minor(this, "Copied data");
			os.close();
			// Must set finished BEFORE calling parentFetcher.
			// Otherwise a race is possible that might result in it not seeing our finishing.
			finished = true;
			if(codec == null || !isCollectingBinaryBlob())
				parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
			if(persistent) container.set(this);
		} catch (IOException e) {
			Logger.normal(this, "Caught bucket error?: "+e, e);
			synchronized(this) {
				finished = true;
				failureException = new FetchException(FetchException.BUCKET_ERROR);
			}
			if(persistent) container.set(this);
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
			return;
		}

		// Now heal

		/** Splitfile healing:
		 * Any block which we have tried and failed to download should be 
		 * reconstructed and reinserted.
		 */

		// Encode any check blocks we don't have
		if(codec != null) {
			codec.addToQueue(new FECJob(codec, context.fecQueue, dataBuckets, checkBuckets, 32768, context.getBucketFactory(parentFetcher.parent.persistent()), this, false, parentFetcher.parent.getPriorityClass(), parentFetcher.parent.persistent()),
					context.fecQueue, container);
		}
	}

	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets2, Bucket[] checkBuckets2, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent)
			container.activate(this, 1);
		// Because we use SplitfileBlock, we DON'T have to copy here.
		// See FECCallback comments for explanation.
		synchronized(this) {
			// Now insert *ALL* blocks on which we had at least one failure, and didn't eventually succeed
			for(int i=0;i<dataBuckets.length;i++) {
				boolean heal = false;
				if(persistent)
					container.activate(dataBuckets[i], 1);
				Bucket data = dataBuckets[i].getData();
				if(persistent)
					container.activate(data, 1);
				if(dataRetries[i] > 0)
					heal = true;
				if(heal) {
					queueHeal(data, context);
				} else {
					dataBuckets[i].data.free();
					dataBuckets[i].data = null;
				}
				dataBuckets[i] = null;
				dataKeys[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				boolean heal = false;
				if(persistent)
					container.activate(checkBuckets[i], 1);
				Bucket data = checkBuckets[i].getData();
				if(persistent)
					container.activate(data, 1);
				try {
					maybeAddToBinaryBlob(data, i, true, container, context);
				} catch (FetchException e) {
					fail(e, container, context);
					return;
				}
				if(checkRetries[i] > 0)
					heal = true;
				if(heal) {
					queueHeal(data, context);
				} else {
					checkBuckets[i].data.free();
				}
				checkBuckets[i] = null;
				checkKeys[i] = null;
			}
		}
		if(persistent)
			container.set(this);
		// Defer the completion until we have generated healing blocks if we are collecting binary blobs.
		if(isCollectingBinaryBlob())
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
	}

	boolean isCollectingBinaryBlob() {
		if(parentFetcher.parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parentFetcher.parent);
			return getter.collectingBinaryBlob();
		} else return false;
	}
	
	private void maybeAddToBinaryBlob(Bucket data, int i, boolean check, ObjectContainer container, ClientContext context) throws FetchException {
		if(parentFetcher.parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parentFetcher.parent);
			if(getter.collectingBinaryBlob()) {
				try {
					ClientCHKBlock block =
						ClientCHKBlock.encode(data, false, true, (short)-1, data.size());
					getter.addKeyToBinaryBlob(block, container, context);
				} catch (CHKEncodeException e) {
					Logger.error(this, "Failed to encode (collecting binary blob) "+(check?"check":"data")+" block "+i+": "+e, e);
					throw new FetchException(FetchException.INTERNAL_ERROR, "Failed to encode for binary blob: "+e);
				} catch (IOException e) {
					throw new FetchException(FetchException.BUCKET_ERROR, "Failed to encode for binary blob: "+e);
				}
			}
		}
	}

	private void queueHeal(Bucket data, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Queueing healing insert");
		context.healingQueue.queue(data, context);
	}
	
	/** This is after any retries and therefore is either out-of-retries or fatal 
	 * @param container */
	public synchronized void onFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Permanently failed block: "+blockNo+" on "+this+" : "+e, e);
		boolean allFailed;
		// Since we can't keep the key, we need to unregister for it at this point to avoid a memory leak
		NodeCHK key = getBlockNodeKey(blockNo);
		if(key != null) seg.unregisterKey(key, context);
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
				parentFetcher.parent.fatallyFailedBlock(container, context);
			} else {
				failedBlocks++;
				parentFetcher.parent.failedBlock(container, context);
			}
			// Once it is no longer possible to have a successful fetch, fail...
			allFailed = failedBlocks + fatallyFailedBlocks > (dataKeys.length + checkKeys.length - minFetched);
		}
		if(persistent)
			container.set(this);
		if(allFailed)
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors), container, context);
		else
			seg.possiblyRemoveFromParent(container);
	}
	
	/** A request has failed non-fatally, so the block may be retried 
	 * @param container */
	public void onNonFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, RequestScheduler sched, ObjectContainer container) {
		if(persistent)
			container.activate(this, 1);
		ClientContext context = sched.getContext();
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
				if(persistent)
					container.activate(key, 5);
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
				if(persistent)
					container.activate(key, 5);
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
		if(persistent)
			container.set(this);
		if(failed) {
			onFatalFailure(e, blockNo, seg, container, context);
			if(logMINOR)
				Logger.minor(this, "Not retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries);
			return;
		}
		if(cooldown) {
			// Register to the next sub-segment before removing from the old one.
			sub.getScheduler(context).addPendingKey(key, sub);
			seg.unregisterKey(key.getNodeKey(), context);
		} else {
			// If we are here we are going to retry
			// Unregister from the old sub-segment before registering on the new.
			seg.unregisterKey(key.getNodeKey(), context);
			if(logMINOR)
				Logger.minor(this, "Retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
			sub.add(blockNo, false, container, context);
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

	void fail(FetchException e, ObjectContainer container, ClientContext context) {
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
		removeSubSegments(container);
		if(persistent)
			container.set(this);
		parentFetcher.segmentFinished(this, container, context);
	}

	public void schedule(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(parentFetcher, 1);
			container.activate(parentFetcher.parent, 1);
		}
		try {
			SplitFileFetcherSubSegment seg = getSubSegment(0);
			if(persistent)
				container.activate(seg, 1);
			for(int i=0;i<dataRetries.length+checkRetries.length;i++)
				seg.add(i, true, container, context);
			
			seg.schedule(container, context);
			synchronized(this) {
				scheduled = true;
			}
			if(persistent)
				container.set(this);
			parentFetcher.parent.notifyClients(container, context);
			if(logMINOR)
				Logger.minor(this, "scheduling "+seg+" : "+seg.blockNums);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t+" scheduling "+this, t);
			fail(new FetchException(FetchException.INTERNAL_ERROR, t), container, context);
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		fail(new FetchException(FetchException.CANCELLED), container, context);
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

	private void removeSubSegments(ObjectContainer container) {
		SplitFileFetcherSubSegment[] deadSegs;
		synchronized(this) {
			deadSegs = (SplitFileFetcherSubSegment[]) subSegments.toArray(new SplitFileFetcherSubSegment[subSegments.size()]);
			subSegments.clear();
		}
		if(persistent && deadSegs.length > 0)
			container.set(this);
		for(int i=0;i<deadSegs.length;i++) {
			deadSegs[i].kill(container);
		}
	}

	public synchronized long getCooldownWakeup(int blockNum) {
		if(blockNum < dataKeys.length)
			return dataCooldownTimes[blockNum];
		else
			return checkCooldownTimes[blockNum - dataKeys.length];
	}

	public void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
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
				sub.add(i, true, container, context);
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
				sub.add(i+dataKeys.length, true, container, context);
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
				((SplitFileFetcherSubSegment) v.get(i)).schedule(container, context);
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

	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) {
				Logger.error(this, "FEC decode or encode failed but already finished: "+t, t);
				return;
			}
			finished = true;
		}
		if(persistent)
			container.activate(this, 1);
		this.fail(new FetchException(FetchException.INTERNAL_ERROR, "FEC failure: "+t, t), container, context);
	}
}
