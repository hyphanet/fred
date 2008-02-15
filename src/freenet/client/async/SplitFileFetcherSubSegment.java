package freenet.client.async;

import java.io.IOException;
import java.util.Vector;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.KeyVerifyException;
import freenet.keys.TooBigException;
import freenet.node.LowLevelGetException;
import freenet.node.SendableGet;
import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 * A sub-segment of a segment of a splitfile being fetched.
 * Collects together all requests within that segment at a given retry level.
 * Registered on the ClientRequestScheduler instead of SimpleSingleFileFetcher's.
 * When CRS asks it to run a request, returns one, and only unregisters if no more requests in this category.
 */
public class SplitFileFetcherSubSegment extends SendableGet {

	final int retryCount;
	final SplitFileFetcherSegment segment;
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
	
	public boolean dontCache() {
		return !ctx.cacheLocalRequests;
	}

	public FetchContext getContext() {
		return ctx;
	}

	public Object chooseKey() {
		if(cancelled) return null;
		return removeRandomBlockNum();
	}
	
	public ClientKey getKey(Object token) {
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
				Logger.error(this, "Segment not finishing yet still returns null for getKey()!: "+token+" for "+this);
			}
		}
		return key;
	}
	
	public synchronized Object[] allKeys() {
		return blockNums.toArray();
	}
	
	private synchronized Object removeRandomBlockNum() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(blockNums.isEmpty()) {
			if(logMINOR)
				Logger.minor(this, "No blocks to remove");
			return null;
		}
		int x = ctx.random.nextInt(blockNums.size());
		Object ret = (Integer) blockNums.remove(x);
		if(logMINOR)
			Logger.minor(this, "Removing block "+x+" of "+(blockNums.size()+1)+ " : "+ret+ " on "+this);
		return ret;
	}

	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	// Translate it, then call the real onFailure
	// FIXME refactor this out to a common method; see SimpleSingleFileFetcher
	public void onFailure(LowLevelGetException e, Object token) {
		if(logMINOR)
			Logger.minor(this, "onFailure("+e+" , "+token);
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), token);
			return;
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), token);
			return;
		case LowLevelGetException.RECENTLY_FAILED:
			onFailure(new FetchException(FetchException.RECENTLY_FAILED), token);
			return;
		case LowLevelGetException.DECODE_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), token);
			return;
		case LowLevelGetException.INTERNAL_ERROR:
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), token);
			return;
		case LowLevelGetException.REJECTED_OVERLOAD:
			onFailure(new FetchException(FetchException.REJECTED_OVERLOAD), token);
			return;
		case LowLevelGetException.ROUTE_NOT_FOUND:
			onFailure(new FetchException(FetchException.ROUTE_NOT_FOUND), token);
			return;
		case LowLevelGetException.TRANSFER_FAILED:
			onFailure(new FetchException(FetchException.TRANSFER_FAILED), token);
			return;
		case LowLevelGetException.VERIFY_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), token);
			return;
		case LowLevelGetException.CANCELLED:
			onFailure(new FetchException(FetchException.CANCELLED), token);
			return;
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), token);
			return;
		}
	}

	// Real onFailure
	protected void onFailure(FetchException e, Object token) {
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
			segment.onNonFatalFailure(e, ((Integer)token).intValue(), this);
		}
	}
	
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object token) {
		Bucket data = extract(block, token);
		if(fromStore) {
			// Normally when this method is called the block number has already
			// been removed. However if fromStore=true, it won't have been, so
			// we have to do it. (Check the call trace for why)
			synchronized(this) {
				for(int i=0;i<blockNums.size();i++) {
					Integer x = (Integer) blockNums.get(i);
					if(x == token) {
						blockNums.remove(i);
						i--;
					}
				}
			}
		}
		if(!block.isMetadata()) {
			onSuccess(data, fromStore, (Integer)token, ((Integer)token).intValue(), block);
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), token);
		}
	}
	
	protected void onSuccess(Bucket data, boolean fromStore, Integer token, int blockNo, ClientKeyBlock block) {
		if(parent.isCancelled()) {
			data.free();
			onFailure(new FetchException(FetchException.CANCELLED), token);
			return;
		}
		segment.onSuccess(data, blockNo, fromStore, this, block);
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block, Object token) {
		Bucket data;
		try {
			data = block.decode(ctx.bucketFactory, (int)(Math.min(ctx.maxOutputLength, Integer.MAX_VALUE)), false);
		} catch (KeyDecodeException e1) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Decode failure: "+e1, e1);
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()), token);
			return null;
		} catch (TooBigException e) {
			onFailure(new FetchException(FetchException.TOO_BIG, e.getMessage()), token);
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e), token);
			return null;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, data == null ? "Could not decode: null" : ("Decoded "+data.size()+" bytes"));
		return data;
	}

	public Object getClient() {
		return segment.parentFetcher.parent.getClient();
	}

	public ClientRequester getClientRequest() {
		return segment.parentFetcher.parent;
	}

	public short getPriorityClass() {
		return segment.parentFetcher.parent.priorityClass;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public synchronized boolean canRemove() {
		if(blockNums.size() < 2) {
			// Can be removed, if the one key is processed.
			// Once it has been processed, we may need to be reinstated.
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Can remove "+this+" in canRemove()");
			return true;
		} else return false;
	}

	public synchronized boolean isCancelled() {
		return cancelled;
	}

	public boolean isSSK() {
		// Not allowed in splitfiles
		return false;
	}

	public void add(int blockNo, boolean dontSchedule) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Adding block "+blockNo+" to "+this+" dontSchedule="+dontSchedule);
		if(blockNo < 0) throw new IllegalArgumentException();
		Integer i = new Integer(blockNo);
		synchronized(this) {
			if(cancelled)
				throw new IllegalStateException("Adding block "+blockNo+" to already cancelled "+this);
			blockNums.add(i);
			if(dontSchedule) return;
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
			if(getParentGrabArray() != null) {
				if(logMINOR) Logger.minor(this, "Already registered, not scheduling: "+blockNums.size()+" : "+blockNums);
				return;
			}
		}
		if(!dontSchedule) schedule();
	}

	public String toString() {
		return super.toString()+":"+retryCount+"/"+segment+'('+blockNums.size()+')';
	}

	public void possiblyRemoveFromParent() {
		if(logMINOR)
			Logger.minor(this, "Possibly removing from parent: "+this);
		synchronized(this) {
			if(!blockNums.isEmpty()) return;
			if(logMINOR)
				Logger.minor(this, "Definitely removing from parent: "+this);
			cancelled = true;
		}
		segment.removeSeg(this);
		unregister();
	}

	public void onGotKey(Key key, KeyBlock block) {
		int blockNum = -1;
		Object token = null;
		ClientKey ckey = null;
		synchronized(this) {
			for(int i=0;i<blockNums.size();i++) {
				token = blockNums.get(i);
				int num = ((Integer)token).intValue();
				ckey = segment.getBlockKey(num);
				if(ckey == null) return; // Already got this key
				Key k = ckey.getNodeKey();
				if(k.equals(key)) {
					blockNum = num;
					blockNums.remove(i);
					break;
				}
			}
		}
		if(blockNum == -1) return;
		try {
			onSuccess(Key.createKeyBlock(ckey, block), false, token);
		} catch (KeyVerifyException e) {
			// FIXME if we ever abolish the direct route, this must be turned into an onFailure().
			Logger.error(this, "Failed to parse in onGotKey("+key+","+block+") - believed to be "+ckey+" (block #"+blockNum+")");
		}
	}
	
	public void kill() {
		if(logMINOR)
			Logger.minor(this, "Killing "+this);
		// Do unregister() first so can get and unregister each key and avoid a memory leak
		unregister();
		synchronized(this) {
			blockNums.clear();
			cancelled = true;
		}
		segment.removeSeg(this);
	}

}
