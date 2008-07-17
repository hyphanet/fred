package freenet.client.async;

import java.io.IOException;
import java.util.Vector;

import com.db4o.ObjectContainer;

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
import freenet.node.RequestClient;
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
	final ClientRequester parent;
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
	
	SplitFileFetcherSubSegment(SplitFileFetcherSegment segment, ClientRequester parent, int retryCount) {
		super(parent);
		this.segment = segment;
		this.retryCount = retryCount;
		this.parent = segment.parent;
		if(parent == null) throw new NullPointerException();
		ctx = segment.blockFetchContext;
		blockNums = new Vector();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public boolean dontCache() {
		return !ctx.cacheLocalRequests;
	}

	public FetchContext getContext() {
		return ctx;
	}

	public Object chooseKey(KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		if(cancelled) return null;
		return removeRandomBlockNum(keys, context, container);
	}
	
	public ClientKey getKey(Object token, ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
		}
		synchronized(segment) {
			if(cancelled) {
				if(logMINOR)
					Logger.minor(this, "Segment is finishing when getting key "+token+" on "+this);
				return null;
			}
			ClientKey key = segment.getBlockKey(((Integer)token).intValue(), container);
			if(key == null) {
				if(segment.isFinished(container)) {
					Logger.error(this, "Segment finished but didn't tell us! "+this);
				} else if(segment.isFinishing(container)) {
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
	public Object[] allKeys(ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
		}
		return segment.getKeyNumbersAtRetryLevel(retryCount);
	}
	
	/**
	 * Just those keys which are eligible to be started now.
	 */
	public Object[] sendableKeys(ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(blockNums, 1);
		}
		cleanBlockNums();
		return blockNums.toArray();
	}
	
	private void cleanBlockNums() {
		synchronized(segment) {
			int initSize = blockNums.size();
			Integer prev = null;
			for(int i=0;i<blockNums.size();i++) {
				Integer x = (Integer) blockNums.get(i);
				if(x == prev || x.equals(prev)) {
					blockNums.remove(i);
					i--;
				}
			}
			if(blockNums.size() < initSize) {
				Logger.error(this, "Cleaned block number list duplicates: was "+initSize+" now "+blockNums.size());
			}
		}
	}

	private Object removeRandomBlockNum(KeysFetchingLocally keys, ClientContext context, ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(blockNums, 1);
			container.activate(segment, 1);
		}
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
				if(blockNums.size() == 0) return null;
				x = context.random.nextInt(blockNums.size());
				ret = (Integer) blockNums.remove(x);
				Key key = segment.getBlockNodeKey(((Integer)ret).intValue(), container);
				if(key == null) {
					if(segment.isFinishing(container) || segment.isFinished(container)) return null;
					if(segment.haveBlock(((Integer)ret).intValue(), container))
						Logger.error(this, "Already have block "+ret+" but was in blockNums on "+this);
					else
						Logger.error(this, "Key is null for block "+ret+" for "+this);
					continue;
				}
				if(keys.hasKey(key)) {
					blockNums.add(ret);
					continue;
				}
				if(logMINOR)
					Logger.minor(this, "Removing block "+x+" of "+(blockNums.size()+1)+ " : "+ret+ " on "+this);
				if(persistent)
					container.set(blockNums);
				return ret;
			}
			return null;
		}
	}

	public boolean hasValidKeys(KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(blockNums, 1);
			container.activate(segment, 1);
		}
		boolean hasSet = false;
		synchronized(segment) {
			for(int i=0;i<10;i++) {
				Object ret;
				int x;
				if(blockNums.isEmpty()) return false;
				x = context.random.nextInt(blockNums.size());
				ret = (Integer) blockNums.get(x);
				Key key = segment.getBlockNodeKey(((Integer)ret).intValue(), container);
				if(key == null) {
					if(segment.isFinishing(container) || segment.isFinished(container)) return false;
					if(segment.haveBlock(((Integer)ret).intValue(), container))
						Logger.error(this, "Already have block "+ret+" but was in blockNums on "+this+" in hasValidKeys");
					else
						Logger.error(this, "Key is null for block "+ret+" for "+this+" in hasValidKeys");
					blockNums.remove(x);
					if(persistent && !hasSet) {
						hasSet = true;
						container.set(blockNums);
					}
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
	
	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	// Translate it, then call the real onFailure
	// FIXME refactor this out to a common method; see SimpleSingleFileFetcher
	public void onFailure(LowLevelGetException e, Object token, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "onFailure("+e+" , "+token+" on "+this);
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), token, container, context);
			return;
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), token, container, context);
			return;
		case LowLevelGetException.RECENTLY_FAILED:
			onFailure(new FetchException(FetchException.RECENTLY_FAILED), token, container, context);
			return;
		case LowLevelGetException.DECODE_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), token, container, context);
			return;
		case LowLevelGetException.INTERNAL_ERROR:
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), token, container, context);
			return;
		case LowLevelGetException.REJECTED_OVERLOAD:
			onFailure(new FetchException(FetchException.REJECTED_OVERLOAD), token, container, context);
			return;
		case LowLevelGetException.ROUTE_NOT_FOUND:
			onFailure(new FetchException(FetchException.ROUTE_NOT_FOUND), token, container, context);
			return;
		case LowLevelGetException.TRANSFER_FAILED:
			onFailure(new FetchException(FetchException.TRANSFER_FAILED), token, container, context);
			return;
		case LowLevelGetException.VERIFY_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), token, container, context);
			return;
		case LowLevelGetException.CANCELLED:
			onFailure(new FetchException(FetchException.CANCELLED), token, container, context);
			return;
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), token, container, context);
			return;
		}
	}

	// Real onFailure
	protected void onFailure(FetchException e, Object token, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(segment, 1);
			container.activate(parent, 1);
			container.activate(segment.errors, 1);
		}
		boolean forceFatal = false;
		if(parent.isCancelled()) {
			if(Logger.shouldLog(Logger.MINOR, this)) 
				Logger.minor(this, "Failing: cancelled");
			e = new FetchException(FetchException.CANCELLED);
			forceFatal = true;
		}
		segment.errors.inc(e.getMode());
		if(e.isFatal() && token == null) {
			segment.fail(e, container, context);
		} else if(e.isFatal() || forceFatal) {
			segment.onFatalFailure(e, ((Integer)token).intValue(), this, container, context);
		} else {
			segment.onNonFatalFailure(e, ((Integer)token).intValue(), this, container, context);
		}
	}
	
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
			container.activate(blockNums, 1);
		}
		Bucket data = extract(block, token, container, context);
		if(fromStore) {
			// Normally when this method is called the block number has already
			// been removed. However if fromStore=true, it won't have been, so
			// we have to do it. (Check the call trace for why)
			boolean removed = false;
			synchronized(segment) {
				for(int i=0;i<blockNums.size();i++) {
					Integer x = (Integer) blockNums.get(i);
					// Compare by value as sometimes we will do new Integer(num) in requeueing after cooldown code.
					if(x.equals(token)) {
						blockNums.remove(i);
						if(logMINOR) Logger.minor(this, "Removed block "+i+" : "+x);
						i--;
						removed = true;
					}
				}
			}
			if(persistent && removed)
				container.set(blockNums);
		}
		if(!block.isMetadata()) {
			onSuccess(data, fromStore, (Integer)token, ((Integer)token).intValue(), block, container, context);
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), token, container, context);
		}
	}
	
	protected void onSuccess(Bucket data, boolean fromStore, Integer token, int blockNo, ClientKeyBlock block, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
			container.activate(parent, 1);
		}
		if(parent.isCancelled()) {
			data.free();
			onFailure(new FetchException(FetchException.CANCELLED), token, container, context);
			return;
		}
		segment.onSuccess(data, blockNo, block, container, context);
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block, Object token, ObjectContainer container, ClientContext context) {
		Bucket data;
		try {
			data = block.decode(context.getBucketFactory(persistent), (int)(Math.min(ctx.maxOutputLength, Integer.MAX_VALUE)), false);
		} catch (KeyDecodeException e1) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Decode failure: "+e1, e1);
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()), token, container, context);
			return null;
		} catch (TooBigException e) {
			onFailure(new FetchException(FetchException.TOO_BIG, e.getMessage()), token, container, context);
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e), token, container, context);
			return null;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, data == null ? "Could not decode: null" : ("Decoded "+data.size()+" bytes"));
		return data;
	}

	public RequestClient getClient() {
		return parent.getClient();
	}

	public ClientRequester getClientRequest() {
		return parent;
	}

	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.priorityClass;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public boolean canRemove(ObjectContainer container) {
		if(persistent)
			container.activate(blockNums, 1);
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

	public boolean isCancelled(ObjectContainer container) {
		if(persistent) {
			container.activate(parent, 1);
		}
		synchronized(segment) {
			return parent.cancelled;
		}
	}
	
	public boolean isEmpty(ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(blockNums, 1);
		}
		synchronized(segment) {
			return cancelled || blockNums.isEmpty();
		}
	}

	public boolean isSSK() {
		// Not allowed in splitfiles
		return false;
	}

	public void add(int blockNo, boolean dontSchedule, ObjectContainer container, ClientContext context, boolean dontComplainOnDupes) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
			container.activate(blockNums, 1);
		}
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Adding block "+blockNo+" to "+this+" dontSchedule="+dontSchedule);
		if(blockNo < 0) throw new IllegalArgumentException();
		Integer i = new Integer(blockNo);
		
		boolean schedule = true;
		synchronized(segment) {
			if(cancelled)
				throw new IllegalStateException("Adding block "+blockNo+" to already cancelled "+this);
			if(blockNums.contains(i)) {
				if(!dontComplainOnDupes)
					Logger.error(this, "Block numbers already contain block "+blockNo);
				else if(logMINOR)
					Logger.minor(this, "Block numbers already contain block "+blockNo);
			} else {
				blockNums.add(i);
			}
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
		if(persistent)
			container.set(blockNums);
		if(schedule)
			context.getChkFetchScheduler().register(null, new SendableGet[] { this }, false, persistent, true, null, null);
	}

	public String toString() {
		return super.toString()+":"+retryCount+"/"+segment+'('+(blockNums == null ? "null" : String.valueOf(blockNums.size()))+')'; 
	}

	public void possiblyRemoveFromParent(ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
			container.activate(blockNums, 1);
		}
		if(logMINOR)
			Logger.minor(this, "Possibly removing from parent: "+this);
		synchronized(segment) {
			if(!blockNums.isEmpty()) return;
			if(logMINOR)
				Logger.minor(this, "Definitely removing from parent: "+this);
			if(!segment.maybeRemoveSeg(this, container)) return;
		}
		unregister(container);
	}

	public void onGotKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
			container.activate(blockNums, 1);
		}
		if(logMINOR) Logger.minor(this, "onGotKey("+key+")");
		// Find and remove block if it is on this subsegment. However it may have been
		// removed already.
		int blockNo;
		synchronized(segment) {
			for(int i=0;i<blockNums.size();i++) {
				Integer token = (Integer) blockNums.get(i);
				int num = ((Integer)token).intValue();
				Key k = segment.getBlockNodeKey(num, container);
				if(k != null && k.equals(key)) {
					blockNums.remove(i);
					break;
				}
			}
			blockNo = segment.getBlockNumber(key, container);
		}
		if(blockNo == -1) {
			Logger.minor(this, "No block found for key "+key+" on "+this);
			return;
		}
		Integer token = new Integer(blockNo);
		ClientCHK ckey = (ClientCHK) segment.getBlockKey(blockNo, container);
		ClientCHKBlock cb;
		try {
			cb = new ClientCHKBlock((CHKBlock)block, ckey);
		} catch (CHKVerifyException e) {
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e), token, container, context);
			return;
		}
		Bucket data = extract(cb, token,  container, context);
		if(data == null) return;
		
		if(!cb.isMetadata()) {
			onSuccess(data, false, (Integer)token, ((Integer)token).intValue(), cb, container, context);
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), token, container, context);
		}
		
	}

	/**
	 * Terminate a subsegment. Called by the segment, which will have already removed the
	 * subsegment from the list.
	 */
	public void kill(ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
			container.activate(blockNums, 1);
		}
		if(logMINOR)
			Logger.minor(this, "Killing "+this);
		// Do unregister() first so can get and unregister each key and avoid a memory leak
		unregister(container);
		synchronized(segment) {
			blockNums.clear();
			cancelled = true;
		}
	}

	public long getCooldownWakeup(Object token, ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
		}
		return segment.getCooldownWakeup(((Integer)token).intValue());
	}

	public void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Requeueing after cooldown "+key+" for "+this);
		if(!segment.requeueAfterCooldown(key, time, container, context)) {
			Logger.error(this, "Key was not wanted after cooldown: "+key+" for "+this+" in requeueAfterCooldown");
		}
	}

	public long getCooldownWakeupByKey(Key key, ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
		}
		return segment.getCooldownWakeupByKey(key, container);
	}

	public void resetCooldownTimes(ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
		}
		synchronized(segment) {
			segment.resetCooldownTimes((Integer[])blockNums.toArray(new Integer[blockNums.size()]));
		}
	}

	public void schedule(ObjectContainer container, ClientContext context, boolean firstTime, boolean regmeOnly) {
		getScheduler(context).register(firstTime ? segment : null, new SendableGet[] { this }, regmeOnly, persistent, true, segment.blockFetchContext.blocks, null);
	}

	public void removeBlockNum(int blockNum, ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "Removing block "+blockNum+" from "+this);
		synchronized(segment) {
			for(int i=0;i<blockNums.size();i++) {
				Integer token = (Integer) blockNums.get(i);
				int num = ((Integer)token).intValue();
				if(num == blockNum) {
					blockNums.remove(i);
					if(logMINOR) Logger.minor(this, "Removed block "+blockNum+" from "+this);
					break;
				}
			}
		}
		container.set(blockNums);
	}

}
