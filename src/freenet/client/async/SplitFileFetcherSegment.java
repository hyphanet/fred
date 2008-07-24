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
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.NodeCHK;
import freenet.keys.TooBigException;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * A single segment within a SplitFileFetcher.
 * This in turn controls a large number of SplitFileFetcherSubSegment's, which are registered on the ClientRequestScheduler.
 */
public class SplitFileFetcherSegment implements FECCallback, GotKeyListener {

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
	
	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.
	
	private final int hashCode;
	
	public int hashCode() {
		return hashCode;
	}
	
	private FECCodec codec;
	
	public SplitFileFetcherSegment(short splitfileType, ClientCHK[] splitfileDataKeys, ClientCHK[] splitfileCheckKeys, SplitFileFetcher fetcher, ArchiveContext archiveContext, FetchContext fetchContext, long maxTempLength, int recursionLevel, ClientRequester requester) throws MetadataParseException, FetchException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.hashCode = super.hashCode();
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

	public synchronized boolean isFinished(ObjectContainer container) {
		if(finished) return true;
		if(persistent) {
			container.activate(parent, 1);
		}
		boolean ret = parent.isCancelled();
		if(persistent)
			container.deactivate(parent, 1);
		return ret;
	}
	
	public synchronized boolean succeeded() {
		return finished;
	}

	public synchronized boolean isFinishing(ObjectContainer container) {
		return isFinished(container) || finishing;
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

	public void onSuccess(Bucket data, int blockNo, ClientKeyBlock block, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		if(data == null) throw new NullPointerException();
		boolean decodeNow = false;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Fetched block "+blockNo+" in "+this);
		if(parent instanceof ClientGetter)
			((ClientGetter)parent).addKeyToBinaryBlob(block, container, context);
		// No need to unregister key, because it will be cleared in tripPendingKey().
		boolean dontNotify;
		synchronized(this) {
			if(finished) {
				// Happens sometimes, don't complain about it...
				// What this means is simply that there were a bunch of requests
				// running, one of them completed, the whole segment went into
				// decode, and now the extra requests are surplus to requirements.
				// It's a slight overhead, but the alternative is worse.
				if(logMINOR)
					Logger.minor(this, "onSuccess() when already finished for "+this);
				return;
			}
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
				if(persistent)
					container.set(dataBuckets[blockNo]);
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
				if(persistent)
					container.set(checkBuckets[blockNo]);
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
		if(persistent) {
			container.set(this);
			container.activate(parent, 1);
		}
		parent.completedBlock(dontNotify, container, context);
		if(decodeNow) {
			context.getChkFetchScheduler().removePendingKeys(this, true);
			removeSubSegments(container);
			decode(container, context);
		}
		if(persistent) {
			container.deactivate(parent, 1);
		}
	}

	public void decode(ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		// Now decode
		if(logMINOR) Logger.minor(this, "Decoding "+SplitFileFetcherSegment.this);

		if(codec == null)
		codec = FECCodec.getCodec(splitfileType, dataKeys.length, checkKeys.length, context.mainExecutor);
		if(persistent)
			container.set(this);
		
		// Activate buckets
		if(persistent) {
			for(int i=0;i<dataBuckets.length;i++)
				container.activate(dataBuckets[i], 1);
		}
		if(persistent) {
			for(int i=0;i<checkBuckets.length;i++)
				container.activate(checkBuckets[i], 1);
		}
		
		if(splitfileType != Metadata.SPLITFILE_NONREDUNDANT) {
			FECQueue queue = context.fecQueue;
			// Double-check...
			int count = 0;
			for(int i=0;i<dataBuckets.length;i++) {
				if(dataBuckets[i].getData() != null)
					count++;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				if(checkBuckets[i].getData() != null)
					count++;
			}
			if(count < dataBuckets.length) {
				Logger.error(this, "Attempting to decode but only "+count+" of "+dataBuckets.length+" blocks available!");
			}
			if(persistent)
				container.activate(parent, 1);
			FECJob job = new FECJob(codec, queue, dataBuckets, checkBuckets, CHKBlock.DATA_LENGTH, context.getBucketFactory(persistent), this, true, parent.getPriorityClass(), persistent);
			codec.addToQueue(job, 
					queue, container);
			if(logMINOR)
				Logger.minor(this, "Queued FEC job: "+job);
			if(persistent)
				container.deactivate(parent, 1);
			// Now have all the data blocks (not necessarily all the check blocks)
		}
	}
	
	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets2, Bucket[] checkBuckets2, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(parentFetcher, 1);
			container.activate(parent, 1);
			container.activate(context, 1);
		}
		// Because we use SplitfileBlock, we DON'T have to copy here.
		// See FECCallback comments for explanation.
		try {
			if(persistent) {
				for(int i=0;i<dataBuckets.length;i++) {
					// The FECCodec won't set them.
					container.set(dataBuckets[i]);
				}
			}
			if(isCollectingBinaryBlob(parent)) {
				for(int i=0;i<dataBuckets.length;i++) {
					if(persistent)
						container.activate(dataBlockStatus[i], 1);
					Bucket data = dataBlockStatus[i].getData();
					try {
						maybeAddToBinaryBlob(data, i, false, container, context);
					} catch (FetchException e) {
						fail(e, container, context, false);
						return;
					}
				}
			}
			decodedData = context.getBucketFactory(persistent).makeBucket(-1);
			if(logMINOR) Logger.minor(this, "Copying data from "+dataBuckets.length+" data blocks");
			OutputStream os = decodedData.getOutputStream();
			for(int i=0;i<dataBuckets.length;i++) {
				if(logMINOR) Logger.minor(this, "Copying data from block "+i);
				SplitfileBlock status = dataBuckets[i];
				if(persistent) container.activate(status, 1);
				if(status == null) throw new NullPointerException();
				Bucket data = status.getData();
				if(data == null) throw new NullPointerException();
				if(persistent) container.activate(data, 1);
				BucketTools.copyTo(data, os, Long.MAX_VALUE);
			}
			if(logMINOR) Logger.minor(this, "Copied data");
			os.close();
			// Must set finished BEFORE calling parentFetcher.
			// Otherwise a race is possible that might result in it not seeing our finishing.
			finished = true;
			if(codec == null || !isCollectingBinaryBlob(parent))
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
		if(codec == null)
			codec = FECCodec.getCodec(splitfileType, dataKeys.length, checkKeys.length, context.mainExecutor);

			codec.addToQueue(new FECJob(codec, context.fecQueue, dataBuckets, checkBuckets, 32768, context.getBucketFactory(parentFetcher.parent.persistent()), this, false, parentFetcher.parent.getPriorityClass(), parentFetcher.parent.persistent()),
					context.fecQueue, container);
		if(persistent) {
			container.deactivate(parentFetcher, 1);
			container.deactivate(parent, 1);
			container.deactivate(context, 1);
		}
	}

	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets2, Bucket[] checkBuckets2, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(parent, 1);
		}
		if(logMINOR)
			Logger.minor(this, "Encoded "+this);
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
				if(data == null) {
					Logger.error(this, "Check block "+i+" is null on "+this);
					continue;
				}
				if(persistent)
					container.activate(data, 1);
				try {
					maybeAddToBinaryBlob(data, i, true, container, context);
				} catch (FetchException e) {
					fail(e, container, context, false);
					return;
				}
				if(checkRetries[i] > 0)
					heal = true;
				if(heal) {
					queueHeal(data, context);
				} else {
					data.free();
				}
				checkBuckets[i] = null;
				checkKeys[i] = null;
			}
		}
		if(persistent) {
			container.set(this);
		}
		// Defer the completion until we have generated healing blocks if we are collecting binary blobs.
		if(isCollectingBinaryBlob(parent)) {
			if(persistent)
				container.activate(parentFetcher, 1);
			parentFetcher.segmentFinished(SplitFileFetcherSegment.this, container, context);
			if(persistent)
				container.deactivate(parentFetcher, 1);
		}
	}

	boolean isCollectingBinaryBlob(ClientRequester parent) {
		if(parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parent);
			return getter.collectingBinaryBlob();
		} else return false;
	}
	
	private void maybeAddToBinaryBlob(Bucket data, int i, boolean check, ObjectContainer container, ClientContext context) throws FetchException {
		if(parent instanceof ClientGetter) {
			ClientGetter getter = (ClientGetter) (parent);
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
		NodeCHK key = getBlockNodeKey(blockNo, container);
		if(key != null)
			// don't complain as may already have been removed e.g. if we have a decode error in onGotKey; don't NPE for same reason
			context.getChkFetchScheduler().removePendingKey(this, false, key, container);
		synchronized(this) {
			if(isFinishing(container)) return; // this failure is now irrelevant, and cleanup will occur on the decoder thread
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
			if(persistent)
				container.activate(parent, 1);
			if(e.isFatal()) {
				fatallyFailedBlocks++;
				parent.fatallyFailedBlock(container, context);
			} else {
				failedBlocks++;
				parent.failedBlock(container, context);
			}
			if(persistent)
				container.deactivate(parent, 1);
			// Once it is no longer possible to have a successful fetch, fail...
			allFailed = failedBlocks + fatallyFailedBlocks > (dataKeys.length + checkKeys.length - minFetched);
		}
		if(persistent)
			container.set(this);
		if(allFailed)
			fail(new FetchException(FetchException.SPLITFILE_ERROR, errors), container, context, false);
		else if(seg != null)
			seg.possiblyRemoveFromParent(container);
	}
	
	/** A request has failed non-fatally, so the block may be retried 
	 * @param container */
	public void onNonFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(blockFetchContext, 1);
		}
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		RequestScheduler sched = context.getFetchScheduler(false);
		boolean set = onNonFatalFailure(e, blockNo, seg, container, context, sched, maxTries);
		if(persistent && set)
			container.set(this);
	}
	
	public void onNonFatalFailure(FetchException[] failures, int[] blockNos, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(blockFetchContext, 1);
		}
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		RequestScheduler sched = context.getFetchScheduler(false);
		boolean set = false;
		for(int i=0;i<failures.length;i++)
			if(onNonFatalFailure(failures[i], blockNos[i], seg, container, context, sched, maxTries))
				set = true;
		if(persistent && set)
			container.set(this);
	}
	
	/**
	 * Caller must set(this) iff returns true.
	 */
	private boolean onNonFatalFailure(FetchException e, int blockNo, SplitFileFetcherSubSegment seg, ObjectContainer container, ClientContext context, RequestScheduler sched, int maxTries) {
		if(logMINOR) Logger.minor(this, "Calling onNonFatalFailure for block "+blockNo+" on "+this+" from "+seg);
		int tries;
		boolean failed = false;
		boolean cooldown = false;
		ClientCHK key;
		SplitFileFetcherSubSegment sub = null;
		synchronized(this) {
			if(isFinished(container)) return false;
			if(blockNo < dataKeys.length) {
				key = dataKeys[blockNo];
				if(persistent)
					container.activate(key, 5);
				tries = ++dataRetries[blockNo];
				if(tries > maxTries && maxTries >= 0) failed = true;
				else {
					sub = getSubSegment(tries, container, false);
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
					sub = getSubSegment(tries, container, false);
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
		if(tries != seg.retryCount+1) {
			Logger.error(this, "Failed on segment "+seg+" but tries for block "+blockNo+" (after increment) is "+tries);
		}
		if(failed) {
			onFatalFailure(e, blockNo, seg, container, context);
			if(logMINOR)
				Logger.minor(this, "Not retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries);
			return false;
		}
		if(cooldown) {
			// Registered to cooldown queue
			if(logMINOR)
				Logger.minor(this, "Added to cooldown queue: "+key+" for "+this+" was on segment "+seg+" now registered to "+sub);
		} else {
			// If we are here we are going to retry
			sub.add(blockNo, false, container, context, false);
			if(logMINOR)
				Logger.minor(this, "Retrying block "+blockNo+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
		}
		if(persistent) {
			if(sub != null && sub != seg) container.deactivate(sub, 1);
			container.deactivate(key, 5);
		}
		return true;
	}
	
	private SplitFileFetcherSubSegment getSubSegment(int retryCount, ObjectContainer container, boolean noCreate) {
		SplitFileFetcherSubSegment sub;
		if(persistent)
			container.activate(subSegments, 1);
		synchronized(this) {
			for(int i=0;i<subSegments.size();i++) {
				sub = (SplitFileFetcherSubSegment) subSegments.get(i);
				if(sub.retryCount == retryCount) return sub;
			}
			if(noCreate) return null;
			if(persistent)
				container.activate(parent, 1);
			sub = new SplitFileFetcherSubSegment(this, parent, retryCount);
			if(persistent)
				container.deactivate(parent, 1);
			subSegments.add(sub);
		}
		if(persistent)
			container.set(subSegments);
		return sub;
	}

	void fail(FetchException e, ObjectContainer container, ClientContext context, boolean dontDeactivateParent) {
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
				if(persistent)
					container.activate(b, 2);
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				dataBuckets[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				MinimalSplitfileBlock b = checkBuckets[i];
				if(persistent)
					container.activate(b, 2);
				if(b != null) {
					Bucket d = b.getData();
					if(d != null) d.free();
				}
				checkBuckets[i] = null;
			}
		}
		context.getChkFetchScheduler().removePendingKeys(this, true);
		removeSubSegments(container);
		if(persistent) {
			container.set(this);
			container.activate(parentFetcher, 1);
		}
		parentFetcher.segmentFinished(this, container, context);
		if(persistent && !dontDeactivateParent)
			container.deactivate(parentFetcher, 1);
	}

	public void schedule(ObjectContainer container, ClientContext context, boolean regmeOnly) {
		if(persistent) {
			container.activate(this, 1);
		}
		try {
			SplitFileFetcherSubSegment seg = getSubSegment(0, container, false);
			if(persistent)
				container.activate(seg, 1);
			seg.addAll(dataRetries.length+checkRetries.length, true, container, context, false);

			if(logMINOR)
				Logger.minor(this, "scheduling "+seg+" : "+seg.blockNums);
			
			seg.schedule(container, context, true, regmeOnly);
			if(persistent)
				container.deactivate(seg, 1);
			synchronized(this) {
				scheduled = true;
			}
			if(persistent)
				container.set(this);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t+" scheduling "+this, t);
			fail(new FetchException(FetchException.INTERNAL_ERROR, t), container, context, true);
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		fail(new FetchException(FetchException.CANCELLED), container, context, true);
	}

	public void onBlockSetFinished(ClientGetState state) {
		// Ignore; irrelevant
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore
	}

	public synchronized ClientCHK getBlockKey(int blockNum, ObjectContainer container) {
		ClientCHK ret;
		if(blockNum < 0) return null;
		else if(blockNum < dataKeys.length)
			ret = dataKeys[blockNum];
		else if(blockNum < dataKeys.length + checkKeys.length)
			ret = checkKeys[blockNum - dataKeys.length];
		else return null;
		if(persistent)
			container.activate(ret, 5);
		return ret;
	}
	
	public NodeCHK getBlockNodeKey(int blockNum, ObjectContainer container) {
		ClientCHK key = getBlockKey(blockNum, container);
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
	public synchronized boolean maybeRemoveSeg(SplitFileFetcherSubSegment segment, ObjectContainer container) {
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
		if(isFinishing(container)) dontRemove = false;
		if(dontRemove) return false;
		if(logMINOR)
			Logger.minor(this, "Removing sub segment: "+segment+" for retry count "+retryCount);
		if(persistent) {
			container.activate(subSegments, 1);
		}
		for(int i=0;i<subSegments.size();i++) {
			if(segment.equals(subSegments.get(i))) {
				subSegments.remove(i);
				i--;
			}
		}
		if(persistent)
			container.set(subSegments);
		return true;
	}

	private void removeSubSegments(ObjectContainer container) {
		if(persistent)
			container.activate(subSegments, 1);
		SplitFileFetcherSubSegment[] deadSegs;
		synchronized(this) {
			deadSegs = (SplitFileFetcherSubSegment[]) subSegments.toArray(new SplitFileFetcherSubSegment[subSegments.size()]);
			subSegments.clear();
		}
		if(persistent && deadSegs.length > 0)
			container.set(this);
		for(int i=0;i<deadSegs.length;i++) {
			if(persistent)
				container.activate(deadSegs[i], 1);
			deadSegs[i].kill(container, true);
		}
		if(persistent) {
			container.set(this);
			container.set(subSegments);
		}
	}

	public synchronized long getCooldownWakeup(int blockNum) {
		if(blockNum < dataKeys.length)
			return dataCooldownTimes[blockNum];
		else
			return checkCooldownTimes[blockNum - dataKeys.length];
	}

	/**
	 * @return True if the key was wanted, false otherwise. 
	 */
	public boolean requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		Vector v = null;
		boolean notFound = true;
		synchronized(this) {
		if(isFinishing(container)) return false;
		int maxTries = blockFetchContext.maxNonSplitfileRetries;
		for(int i=0;i<dataKeys.length;i++) {
			if(dataKeys[i] == null) continue;
			ClientKey k = dataKeys[i];
			if(persistent)
				container.activate(k, 5);
			if(k.getNodeKey().equals(key)) {
				if(dataCooldownTimes[i] > time) {
					if(logMINOR)
						Logger.minor(this, "Not retrying after cooldown for data block "+i+" as deadline has not passed yet on "+this+" remaining time: "+(dataCooldownTimes[i]-time)+"ms");
					return false;
				}
				int tries = dataRetries[i];
				SplitFileFetcherSubSegment sub = getSubSegment(tries, container, false);
				if(logMINOR)
					Logger.minor(this, "Retrying after cooldown on "+this+": data block "+i+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
				if(v == null) v = new Vector();
				sub.add(i, true, container, context, true);
				if(!v.contains(sub)) v.add(sub);
				notFound = false;
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		for(int i=0;i<checkKeys.length;i++) {
			if(checkKeys[i] == null) continue;
			ClientKey k = checkKeys[i];
			if(persistent)
				container.activate(k, 5);
			if(k.getNodeKey().equals(key)) {
				if(checkCooldownTimes[i] > time) {
					if(logMINOR)
						Logger.minor(this, "Not retrying after cooldown for check block "+i+" as deadline has not passed yet on "+this+" remaining time: "+(checkCooldownTimes[i]-time)+"ms");
					return false;
				}
				int tries = checkRetries[i];
				SplitFileFetcherSubSegment sub = getSubSegment(tries, container, false);
				if(logMINOR)
					Logger.minor(this, "Retrying after cooldown on "+this+": check block "+i+" on "+this+" : tries="+tries+"/"+maxTries+" : "+sub);
				if(v == null) v = new Vector();
				sub.add(i+dataKeys.length, true, container, context, true);
				if(!v.contains(sub)) v.add(sub);
				notFound = false;
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		}
		if(notFound) {
			Logger.error(this, "requeueAfterCooldown: Key not found!: "+key+" on "+this);
		}
		if(v != null) {
			for(int i=0;i<v.size();i++) {
				SplitFileFetcherSubSegment sub = (SplitFileFetcherSubSegment) v.get(i);
				if(persistent)
					container.activate(sub, 1);
				RandomGrabArray rga = sub.getParentGrabArray();
				if(sub.getParentGrabArray() == null) {
					sub.schedule(container, context, false, false);
				} else {
//					if(logMINOR) {
						if(persistent)
							container.activate(rga, 1);
						if(!rga.contains(sub, container)) {
							Logger.error(this, "Sub-segment has RGA but isn't registered to it!!: "+sub+" for "+rga);
							sub.schedule(container, context, false, false);
						}
						if(persistent)
							container.deactivate(rga, 1);
//					}
				}
				if(persistent)
					container.deactivate(sub, 1);
			}
		}
		return true;
	}

	public synchronized long getCooldownWakeupByKey(Key key, ObjectContainer container) {
		for(int i=0;i<dataKeys.length;i++) {
			if(dataKeys[i] == null) continue;
			ClientKey k = dataKeys[i];
			if(persistent)
				container.activate(k, 5);
			if(k.getNodeKey().equals(key)) {
				return dataCooldownTimes[i];
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		for(int i=0;i<checkKeys.length;i++) {
			if(checkKeys[i] == null) continue;
			ClientKey k = checkKeys[i];
			if(persistent)
				container.activate(k, 5);
			if(checkKeys[i].getNodeKey().equals(key)) {
				return checkCooldownTimes[i];
			} else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		return -1;
	}

	public synchronized int getBlockNumber(Key key, ObjectContainer container) {
		for(int i=0;i<dataKeys.length;i++) {
			ClientKey k = dataKeys[i];
			if(k == null) continue;
			if(persistent)
				container.activate(k, 5);
			if(k.getNodeKey().equals(key)) return i;
			else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
		for(int i=0;i<checkKeys.length;i++) {
			ClientKey k = checkKeys[i];
			if(k == null) continue;
			if(persistent)
				container.activate(k, 5);
			if(k.getNodeKey().equals(key)) return dataKeys.length+i;
			else {
				if(persistent)
					container.deactivate(k, 5);
			}
		}
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
		this.fail(new FetchException(FetchException.INTERNAL_ERROR, "FEC failure: "+t, t), container, context, false);
	}

	public boolean haveBlock(int blockNo, ObjectContainer container) {
		if(blockNo < dataBuckets.length) {
			if(dataBuckets[blockNo] == null) return false;
			if(persistent) container.activate(dataBuckets[blockNo], 1);
			boolean retval = dataBuckets[blockNo].hasData();
			if(persistent) container.deactivate(dataBuckets[blockNo], 1);
			return retval;
		} else {
			blockNo -= dataBuckets.length;
			if(checkBuckets[blockNo] != null) return false;
			if(persistent) container.activate(checkBuckets[blockNo], 1);
			boolean retval = checkBuckets[blockNo].hasData();
			if(persistent) container.deactivate(checkBuckets[blockNo], 1);
			return retval;
		}
	}

	public boolean dontCache(ObjectContainer container) {
		return !blockFetchContext.cacheLocalRequests;
	}

	public short getPriorityClass(ObjectContainer container) {
		if(persistent)
			container.activate(parent, 1);
		return parent.priorityClass;
	}

	public SendableGet getRequest(Key key, ObjectContainer container) {
		int blockNum = this.getBlockNumber(key, container);
		if(blockNum < 0) return null;
		int retryCount = getBlockRetryCount(blockNum);
		return getSubSegment(retryCount, container, false);
	}

	public boolean isCancelled(ObjectContainer container) {
		return isFinishing(container);
	}

	public Key[] listKeys(ObjectContainer container) {
		Vector v = new Vector();
		synchronized(this) {
			for(int i=0;i<dataKeys.length;i++) {
				if(dataKeys[i] != null) {
					if(persistent)
						container.activate(dataKeys[i], 5);
					v.add(dataKeys[i].getNodeKey());
				}
			}
			for(int i=0;i<checkKeys.length;i++) {
				if(checkKeys[i] != null) {
					if(persistent)
						container.activate(checkKeys[i], 5);
					v.add(checkKeys[i].getNodeKey());
				}
			}
		}
		return (Key[]) v.toArray(new Key[v.size()]);
	}

	public void onGotKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		int blockNum = this.getBlockNumber(key, container);
		if(blockNum < 0) return;
		ClientCHK ckey = this.getBlockKey(blockNum, container);
		ClientCHKBlock cb;
		int retryCount = getBlockRetryCount(blockNum);
		SplitFileFetcherSubSegment seg = this.getSubSegment(retryCount, container, true);
		if(persistent)
			container.activate(seg, 1);
		if(seg != null) {
			seg.removeBlockNum(blockNum, container);
			seg.possiblyRemoveFromParent(container);
		}
		for(int i=0;i<subSegments.size();i++) {
			SplitFileFetcherSubSegment checkSeg = (SplitFileFetcherSubSegment) subSegments.get(i);
			if(checkSeg == seg) continue;
			if(persistent)
				container.activate(checkSeg, 1);
			if(checkSeg.removeBlockNum(blockNum, container))
				Logger.error(this, "Block number "+blockNum+" was registered to wrong subsegment "+checkSeg+" should be "+seg);
			if(persistent)
				container.deactivate(checkSeg, 1);
		}
		if(persistent)
			container.deactivate(seg, 1);
		try {
			cb = new ClientCHKBlock((CHKBlock)block, ckey);
		} catch (CHKVerifyException e) {
			this.onFatalFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e), blockNum, null, container, context);
			return;
		}
		Bucket data = extract(cb, blockNum, container, context);
		if(data == null) return;
		
		if(!cb.isMetadata()) {
			this.onSuccess(data, blockNum, cb, container, context);
		} else {
			this.onFatalFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), blockNum, null, container, context);
		}
	}
	
	private int getBlockRetryCount(int blockNum) {
		if(blockNum < dataRetries.length)
			return dataRetries[blockNum];
		blockNum -= dataRetries.length;
		return checkRetries[blockNum];
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block, int blockNum, ObjectContainer container, ClientContext context) {
		Bucket data;
		try {
			data = block.decode(context.getBucketFactory(persistent), (int)(Math.min(this.blockFetchContext.maxOutputLength, Integer.MAX_VALUE)), false);
		} catch (KeyDecodeException e1) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Decode failure: "+e1, e1);
			this.onFatalFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()), blockNum, null, container, context);
			return null;
		} catch (TooBigException e) {
			this.onFatalFailure(new FetchException(FetchException.TOO_BIG, e.getMessage()), blockNum, null, container, context);
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			this.onFatalFailure(new FetchException(FetchException.BUCKET_ERROR, e), blockNum, null, container, context);
			return null;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, data == null ? "Could not decode: null" : ("Decoded "+data.size()+" bytes"));
		return data;
	}


	public boolean persistent() {
		return persistent;
	}

	public void deactivateKeys(ObjectContainer container) {
		for(int i=0;i<dataKeys.length;i++)
			container.deactivate(dataKeys[i], 1);
		for(int i=0;i<checkKeys.length;i++)
			container.deactivate(checkKeys[i], 1);
	}
}
