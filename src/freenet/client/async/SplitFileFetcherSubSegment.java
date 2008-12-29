package freenet.client.async;

import java.io.IOException;
import java.util.Vector;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.TooBigException;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 * A sub-segment of a segment of a splitfile being fetched.
 * Collects together all requests within that segment at a given retry level.
 * Registered on the ClientRequestScheduler instead of SimpleSingleFileFetcher's.
 * When CRS asks it to run a request, returns one, and only unregisters if no more requests in this category.
 * 
 * LOCKING: Synchronize on the parent segment. Nothing else makes sense w.r.t. nested locking.
 * Note that SendableRequest will occasionally lock on (this). That lock is always taken last.
 */
public class SplitFileFetcherSubSegment extends SendableGet {

	final int retryCount;
	final SplitFileFetcherSegment segment;
	/**
	 * The block numbers (as Integer's) of the blocks we are currently trying to fetch.
	 * Does not include blocks on the cooldown queue, this is simply used to make 
	 * chooseKey() and allKeys() work / work fast. The retries tables in the Segment are
	 * canonical.
	 */
	final Vector blockNums;
	final FetchContext ctx;
	private static boolean logMINOR;
	private boolean cancelled;
	
	SplitFileFetcherSubSegment(SplitFileFetcherSegment segment, int retryCount) {
		super(segment.parentFetcher.parent);
		this.segment = segment;
		this.retryCount = retryCount;
		ctx = segment.blockFetchContext;
		blockNums = new Vector();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	@Override
	public boolean dontCache() {
		return !ctx.cacheLocalRequests;
	}

	@Override
	public FetchContext getContext() {
		return ctx;
	}

	@Override
	public Object chooseKey(KeysFetchingLocally keys) {
		if(cancelled) return null;
		return removeRandomBlockNum(keys);
	}
	
	@Override
	public ClientKey getKey(Object token) {
		synchronized(segment) {
			if(cancelled) {
				if(logMINOR)
					Logger.minor(this, "Segment is finishing when getting key "+token+" on "+this);
				return null;
			}
			ClientKey key = segment.getBlockKey(((Integer)token).intValue());
			if(key == null) {
				if(segment.isFinished()) {
					Logger.error(this, "Segment finished but didn't tell us! "+this);
				} else if(segment.isFinishing()) {
					Logger.error(this, "Segment finishing but didn't tell us! "+this);
				} else {
					Logger.error(this, "Segment not finishing yet still returns null for getKey()!: "+token+" for "+this, new Exception("debug"));
				}
			}
			return key;
		}
	}
	
	/**
	 * Fetch the array from the segment because we need to include *ALL* keys, especially
	 * those on cooldown queues. This is important when unregistering.
	 */
	@Override
	public Object[] allKeys() {
		// j16sdiz (22-DEC-2008):
		// ClientRequestSchedular.removePendingKeys() call this to get a list of request to be removed
		// FIXME ClientRequestSchedular.removePendingKeys() is leaking, what's missing here?
		return segment.getKeyNumbersAtRetryLevel(retryCount);
	}
	
	/**
	 * Just those keys which are eligible to be started now.
	 */
	@Override
	public Object[] sendableKeys() {
		return blockNums.toArray();
	}
	
	private Object removeRandomBlockNum(KeysFetchingLocally keys) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(segment) {
			if(blockNums.isEmpty()) {
				if(logMINOR)
					Logger.minor(this, "No blocks to remove");
				return null;
			}
			for(int i=0;i<10;i++) {
				Object ret;
				int x;
				x = ctx.random.nextInt(blockNums.size());
				ret = (Integer) blockNums.remove(x);
				Key key = segment.getBlockNodeKey(((Integer)ret).intValue());
				if(key == null) {
					if(segment.isFinishing() || segment.isFinished()) return null;
					Logger.error(this, "Key is null for block "+ret+" for "+this);
					continue;
				}
				if(keys.hasKey(key)) {
					blockNums.add(ret);
					continue;
				}
				if(logMINOR)
					Logger.minor(this, "Removing block "+x+" of "+(blockNums.size()+1)+ " : "+ret+ " on "+this);
				return ret;
			}
			return null;
		}
	}

	@Override
	public boolean hasValidKeys(KeysFetchingLocally keys) {
		synchronized(segment) {
			for(int i=0;i<10;i++) {
				Object ret;
				int x;
				if(blockNums.isEmpty()) return false;
				x = ctx.random.nextInt(blockNums.size());
				ret = (Integer) blockNums.get(x);
				Key key = segment.getBlockNodeKey(((Integer)ret).intValue());
				if(key == null) {
					Logger.error(this, "Key is null for block "+ret+" for "+this+" in hasValidKeys()");
					blockNums.remove(x);
					continue;
				}
				if(keys.hasKey(key)) {
					continue;
				}
				return true;
			}
			return false;
		}
	}
	
	@Override
	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	// Translate it, then call the real onFailure
	// FIXME refactor this out to a common method; see SimpleSingleFileFetcher
	@Override
	public void onFailure(LowLevelGetException e, Object token, RequestScheduler sched) {
		if(logMINOR)
			Logger.minor(this, "onFailure("+e+" , "+token);
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), token, sched);
			return;
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), token, sched);
			return;
		case LowLevelGetException.RECENTLY_FAILED:
			onFailure(new FetchException(FetchException.RECENTLY_FAILED), token, sched);
			return;
		case LowLevelGetException.DECODE_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), token, sched);
			return;
		case LowLevelGetException.INTERNAL_ERROR:
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), token, sched);
			return;
		case LowLevelGetException.REJECTED_OVERLOAD:
			onFailure(new FetchException(FetchException.REJECTED_OVERLOAD), token, sched);
			return;
		case LowLevelGetException.ROUTE_NOT_FOUND:
			onFailure(new FetchException(FetchException.ROUTE_NOT_FOUND), token, sched);
			return;
		case LowLevelGetException.TRANSFER_FAILED:
			onFailure(new FetchException(FetchException.TRANSFER_FAILED), token, sched);
			return;
		case LowLevelGetException.VERIFY_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), token, sched);
			return;
		case LowLevelGetException.CANCELLED:
			onFailure(new FetchException(FetchException.CANCELLED), token, sched);
			return;
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), token, sched);
			return;
		}
	}

	// Real onFailure
	protected void onFailure(FetchException e, Object token, RequestScheduler sched) {
		boolean forceFatal = false;
		if(parent.isCancelled()) {
			if(Logger.shouldLog(Logger.MINOR, this)) 
				Logger.minor(this, "Failing: cancelled");
			e = new FetchException(FetchException.CANCELLED);
			forceFatal = true;
		}
		segment.errors.inc(e.getMode());
		if(e.isFatal() || forceFatal) {
			segment.onFatalFailure(e, ((Integer)token).intValue(), this);
		} else {
			segment.onNonFatalFailure(e, ((Integer)token).intValue(), this, sched);
		}
	}
	
	@Override
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, RequestScheduler sched) {
		Bucket data = extract(block, token, sched);
		if(fromStore) {
			// Normally when this method is called the block number has already
			// been removed. However if fromStore=true, it won't have been, so
			// we have to do it. (Check the call trace for why)
			synchronized(segment) {
				for(int i=0;i<blockNums.size();i++) {
					Integer x = (Integer) blockNums.get(i);
					// Compare by value as sometimes we will do new Integer(num) in requeueing after cooldown code.
					if(x.equals(token)) {
						blockNums.remove(i);
						if(logMINOR) Logger.minor(this, "Removed block "+i+" : "+x);
						i--;
					}
				}
			}
		}
		if(!block.isMetadata()) {
			onSuccess(data, fromStore, (Integer)token, ((Integer)token).intValue(), block, sched);
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), token, sched);
			data.free();
		}
	}
	
	protected void onSuccess(Bucket data, boolean fromStore, Integer token, int blockNo, ClientKeyBlock block, RequestScheduler sched) {
		if(parent.isCancelled()) {
			data.free();
			onFailure(new FetchException(FetchException.CANCELLED), token, sched);
			return;
		}
		segment.onSuccess(data, blockNo, this, block);
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block, Object token, RequestScheduler sched) {
		Bucket data;
		try {
			data = block.decode(ctx.bucketFactory, (int)(Math.min(ctx.maxOutputLength, Integer.MAX_VALUE)), false);
		} catch (KeyDecodeException e1) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Decode failure: "+e1, e1);
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()), token, sched);
			return null;
		} catch (TooBigException e) {
			onFailure(new FetchException(FetchException.TOO_BIG, e.getMessage()), token, sched);
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e), token, sched);
			return null;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, data == null ? "Could not decode: null" : ("Decoded "+data.size()+" bytes"));
		return data;
	}

	@Override
	public Object getClient() {
		return segment.parentFetcher.parent.getClient();
	}

	@Override
	public ClientRequester getClientRequest() {
		return segment.parentFetcher.parent;
	}

	@Override
	public short getPriorityClass() {
		return segment.parentFetcher.parent.priorityClass;
	}

	@Override
	public int getRetryCount() {
		return retryCount;
	}

	public boolean canRemove() {
		synchronized(segment) {
			if(blockNums.size() < 2) {
				// Can be removed, if the one key is processed.
				// Once it has been processed, we may need to be reinstated.
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Can remove "+this+" in canRemove()");
				return true;
			} else return false;
		}
	}

	@Override
	public boolean isCancelled() {
		synchronized(segment) {
			return cancelled;
		}
	}
	
	public boolean isEmpty() {
		synchronized(segment) {
			return cancelled || blockNums.isEmpty();
		}
	}

	@Override
	public boolean isSSK() {
		// Not allowed in splitfiles
		return false;
	}

	public void add(int blockNo, boolean dontSchedule) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Adding block "+blockNo+" to "+this+" dontSchedule="+dontSchedule);
		if(blockNo < 0) throw new IllegalArgumentException();
		Integer i = Integer.valueOf(blockNo);
		
		boolean schedule = true;
		synchronized(segment) {
			if(cancelled)
				throw new IllegalStateException("Adding block "+blockNo+" to already cancelled "+this);
			blockNums.add(i);
			if(dontSchedule) schedule = false;
			/**
			 * Race condition:
			 * 
			 * Starter thread sees there is only one block on us, so removes us.
			 * Another thread adds a block. We don't schedule as we now have two blocks.
			 * Starter thread removes us.
			 * Other blocks may be added later, but we are never rescheduled.
			 * 
			 * Fixing this by only removing the SendableRequest after we've removed the 
			 * block is nontrivial with the current code.
			 * So what we do here is simply check whether we are registered, instead of 
			 * checking whether blockNums.size() > 1 as we used to.
			 */
			if(schedule && getParentGrabArray() != null) {
				if(logMINOR) Logger.minor(this, "Already registered, not scheduling: "+blockNums.size()+" : "+blockNums);
				schedule = false;
			}
		}
		if(schedule) schedule();
		else if(!dontSchedule)
			// Already scheduled, however this key may not be registered.
			getScheduler().addPendingKey(segment.getBlockKey(blockNo), this);
	}

	@Override
	public String toString() {
		return super.toString()+":"+retryCount+"/"+segment+'('+blockNums.size()+')'; 
	}

	public void possiblyRemoveFromParent() {
		if(logMINOR)
			Logger.minor(this, "Possibly removing from parent: "+this);
		synchronized(segment) {
			if(!blockNums.isEmpty()) return;
			if(logMINOR)
				Logger.minor(this, "Definitely removing from parent: "+this);
			if(!segment.maybeRemoveSeg(this)) return;
			cancelled = true;
		}
		unregister(false);
	}

	@Override
	public void onGotKey(Key key, KeyBlock block, RequestScheduler sched) {
		if(logMINOR) Logger.minor(this, "onGotKey("+key+")");
		// Find and remove block if it is on this subsegment. However it may have been
		// removed already.
		int blockNo;
		synchronized(segment) {
			for(int i=0;i<blockNums.size();i++) {
				Integer token = (Integer) blockNums.get(i);
				int num = ((Integer)token).intValue();
				Key k = segment.getBlockNodeKey(num);
				if(k != null && k.equals(key)) {
					blockNums.remove(i);
					break;
				}
			}
			blockNo = segment.getBlockNumber(key);
		}
		if(blockNo == -1) {
			Logger.minor(this, "No block found for key "+key+" on "+this);
			return;
		}
		Integer token = Integer.valueOf(blockNo);
		ClientCHK ckey = (ClientCHK) segment.getBlockKey(blockNo);
		ClientCHKBlock cb;
		try {
			cb = new ClientCHKBlock((CHKBlock)block, ckey);
		} catch (CHKVerifyException e) {
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e), token, sched);
			return;
		}
		Bucket data = extract(cb, token, sched);
		
		if(!cb.isMetadata()) {
			onSuccess(data, false, (Integer)token, ((Integer)token).intValue(), cb, sched);
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), token, sched);
		}
		
	}

	/**
	 * Terminate a subsegment. Called by the segment, which will have already removed the
	 * subsegment from the list.
	 */
	public void kill() {
		if(logMINOR)
			Logger.minor(this, "Killing "+this);
		// Do unregister() first so can get and unregister each key and avoid a memory leak
		unregister(false);
		synchronized(segment) {
			blockNums.clear();
			cancelled = true;
		}
	}

	@Override
	public long getCooldownWakeup(Object token) {
		return segment.getCooldownWakeup(((Integer)token).intValue());
	}

	@Override
	public void requeueAfterCooldown(Key key, long time) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Requeueing after cooldown "+key+" for "+this);
		segment.requeueAfterCooldown(key, time);
	}

	@Override
	public long getCooldownWakeupByKey(Key key) {
		return segment.getCooldownWakeupByKey(key);
	}

	@Override
	public void resetCooldownTimes() {
		synchronized(segment) {
			segment.resetCooldownTimes((Integer[])blockNums.toArray(new Integer[blockNums.size()]));
		}
	}

}
