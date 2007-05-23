package freenet.client.async;

import java.io.IOException;
import java.util.Vector;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyDecodeException;
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

	public int chooseKey() {
		if(segment.isFinishing()) return -1;
		return removeRandomBlockNum();
	}
	
	public ClientKey getKey(int token) {
		if(segment.isFinishing()) {
			if(logMINOR)
				Logger.minor(this, "Segment is finishing when getting key "+token+" on "+this);
			return null;
		}
		return segment.getBlockKey(token);
	}
	
	public synchronized int[] allKeys() {
		int[] nums = new int[blockNums.size()];
		for(int i=0;i<nums.length;i++)
			nums[i] = ((Integer) blockNums.get(i)).intValue();
		return nums;
	}
	
	private synchronized int removeRandomBlockNum() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(blockNums.isEmpty()) {
			if(logMINOR)
				Logger.minor(this, "No blocks to remove");
			return -1;
		}
		int x = ctx.random.nextInt(blockNums.size());
		int ret = ((Integer) blockNums.remove(x)).intValue();
		if(logMINOR)
			Logger.minor(this, "Removing block "+x+" of "+(blockNums.size()+1)+ " : "+ret);
		return ret;
	}

	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	// Translate it, then call the real onFailure
	// FIXME refactor this out to a common method; see SimpleSingleFileFetcher
	public void onFailure(LowLevelGetException e, int token) {
		if(logMINOR)
			Logger.minor(this, "onFailure("+e+" , "+token);
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), token);
			return;
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), token);
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
	protected void onFailure(FetchException e, int token) {
		boolean forceFatal = false;
		if(parent.isCancelled()) {
			if(Logger.shouldLog(Logger.MINOR, this)) 
				Logger.minor(this, "Failing: cancelled");
			e = new FetchException(FetchException.CANCELLED);
			forceFatal = true;
		}
		segment.errors.inc(e.getMode());
		if(e.isFatal() || forceFatal) {
			segment.onFatalFailure(e, token, this);
		} else {
			segment.onNonFatalFailure(e, token, this);
		}
	}
	
	public void onSuccess(ClientKeyBlock block, boolean fromStore, int token) {
		Bucket data = extract(block, token);
		if(fromStore) {
			// Normally when this method is called the block number has already
			// been removed. However if fromStore=true, it won't have been, so
			// we have to do it. (Check the call trace for why)
			synchronized(this) {
				for(int i=0;i<blockNums.size();i++) {
					Integer x = (Integer) blockNums.get(i);
					if(x.intValue() == token) {
						blockNums.remove(i);
						i--;
					}
				}
			}
		}
		if(!block.isMetadata()) {
			onSuccess(data, fromStore, token, block);
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), token);
		}
	}
	
	protected void onSuccess(Bucket data, boolean fromStore, int blockNo, ClientKeyBlock block) {
		if(parent.isCancelled()) {
			data.free();
			onFailure(new FetchException(FetchException.CANCELLED), blockNo);
			return;
		}
		segment.onSuccess(data, blockNo, fromStore, this, block);
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block, int token) {
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

	public boolean isCancelled() {
		return segment.isFinished() || segment.isFinishing();
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
			blockNums.add(i);
			if(dontSchedule) return;
			if(blockNums.size() > 1) {
				if(logMINOR) Logger.minor(this, "Other blocks queued, not scheduling: "+blockNums.size()+" : "+blockNums);
				return;
			}
		}
		if(!dontSchedule) schedule();
	}

	public String toString() {
		return super.toString()+":"+retryCount+"/"+segment+'('+blockNums.size()+')';
	}

	public synchronized void possiblyRemoveFromParent() {
		if(blockNums.isEmpty())
			segment.removeSeg(this);
	}
	
}
