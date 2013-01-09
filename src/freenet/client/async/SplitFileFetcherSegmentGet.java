package freenet.client.async;

import java.util.ArrayList;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.BulkCallFailureItem;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableRequestItem;
import freenet.node.SupportsBulkCallFailure;
import freenet.support.ListUtils;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/** This was going to be part of SplitFileFetcherSegment, but we can't add a new parent
 * class to the class hierarchy in db4o without quite a bit of work.
 * 
 * Anyway we can probably improve things this way ... This class can keep track of retries
 * and cooldowns, we can remove them from the segment itself, meaning the segment could
 * eventually be shared by multiple getters running at different priorities, and would
 * eventually only be concerned with FEC decoding and data buckets ...
 * @author toad
 */
public class SplitFileFetcherSegmentGet extends SendableGet implements SupportsBulkCallFailure {
	
	public SplitFileFetcherSegmentGet(ClientRequester parent, SplitFileFetcherSegment segment, boolean realTimeFlag) {
		super(parent, realTimeFlag);
		this.segment = segment;
	}

	public final SplitFileFetcherSegment segment;

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public boolean isEmpty(ObjectContainer container) {
		if(persistent) container.activate(segment, 1);
		return segment.isFinishing(container);
	}

	@Override
	public ClientKey getKey(Object token, ObjectContainer container) {
		SplitFileFetcherSegmentSendableRequestItem req = (SplitFileFetcherSegmentSendableRequestItem) token;
		if(persistent) container.activate(segment, 1);
		return segment.getBlockKey(req.blockNum, container);
	}

	@Override
	public Key[] listKeys(ObjectContainer container) {
		boolean activated = false;
		if(persistent) {
			activated = container.ext().isActive(segment);
			if(!activated)
				container.activate(segment, 1);
		}
		Key[] keys = segment.listKeys(container);
		if(persistent && !activated)
			container.deactivate(segment, 1);
		return keys;
	}

	@Override
	public FetchContext getContext(ObjectContainer container) {
		boolean segmentActive = true;
		if(persistent) {
			segmentActive = container.ext().isActive(segment);
			if(!segmentActive) container.activate(segment, 1);
		}
		FetchContext ctx = segment.blockFetchContext;
		if(!segmentActive) container.deactivate(segment, 1);
		if(persistent) container.activate(ctx, 1);
		return ctx;
	}
	
	private boolean localRequestOnly(ObjectContainer container,
			ClientContext context) {
		boolean localOnly = false;
		boolean segmentActive = true;
		boolean ctxActive = true;
		if(persistent) {
			segmentActive = container.ext().isActive(segment);
			if(!segmentActive) container.activate(segment, 1);
		}
		FetchContext ctx = segment.blockFetchContext;
		if(!segmentActive) container.deactivate(segment, 1);
		if(persistent) {
			ctxActive = container.ext().isActive(ctx);
			container.activate(ctx, 1);
		}
		localOnly = ctx.localRequestOnly;
		if(!ctxActive) container.deactivate(ctx, 1);
		return localOnly;
	}

	// FIXME refactor this out to a common method; see SimpleSingleFileFetcher
	private FetchException translateException(LowLevelGetException e) {
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			return new FetchException(FetchException.DATA_NOT_FOUND);
		case LowLevelGetException.RECENTLY_FAILED:
			return new FetchException(FetchException.RECENTLY_FAILED);
		case LowLevelGetException.DECODE_FAILED:
			return new FetchException(FetchException.BLOCK_DECODE_ERROR);
		case LowLevelGetException.INTERNAL_ERROR:
			return new FetchException(FetchException.INTERNAL_ERROR);
		case LowLevelGetException.REJECTED_OVERLOAD:
			return new FetchException(FetchException.REJECTED_OVERLOAD);
		case LowLevelGetException.ROUTE_NOT_FOUND:
			return new FetchException(FetchException.ROUTE_NOT_FOUND);
		case LowLevelGetException.TRANSFER_FAILED:
			return new FetchException(FetchException.TRANSFER_FAILED);
		case LowLevelGetException.VERIFY_FAILED:
			return new FetchException(FetchException.BLOCK_DECODE_ERROR);
		case LowLevelGetException.CANCELLED:
			return new FetchException(FetchException.CANCELLED);
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			return new FetchException(FetchException.INTERNAL_ERROR, "Unknown error code: "+e.code);
		}
	}

	@Override
	public void onFailure(LowLevelGetException e, Object token,
			ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "onFailure("+e+" , "+token+" on "+this);
		onFailure(translateException(e), token, container, context);
	}
	
	public void onFailure(FetchException e, Object token,
			ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(segment, 1);
			container.activate(parent, 1);
			container.activate(segment.errors, 1);
		}
		boolean forceFatal = false;
		if(parent.isCancelled()) {
			if(logMINOR)
				Logger.minor(this, "Failing: cancelled");
			e = new FetchException(FetchException.CANCELLED);
			forceFatal = true;
		}
		segment.errors.inc(e.getMode());
		if(persistent)
			segment.errors.storeTo(container);
		if(e.isFatal() && token == null) {
			segment.fail(e, container, context, false);
		} else if(e.isFatal() || forceFatal) {
			segment.onFatalFailure(e, ((SplitFileFetcherSegmentSendableRequestItem)token).blockNum, container, context);
		} else {
			segment.onNonFatalFailure(e, ((SplitFileFetcherSegmentSendableRequestItem)token).blockNum, container, context);
		}
		if(persistent) {
			container.deactivate(segment, 1);
			container.deactivate(parent, 1);
			container.deactivate(segment.errors, 1);
		}
	}

	@Override
	public long getCooldownWakeup(Object token, ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		return segment.getCooldownWakeup(((SplitFileFetcherSegmentSendableRequestItem)token).blockNum, segment.getMaxRetries(container), container, context);
	}

	@Override
	public long getCooldownWakeupByKey(Key key, ObjectContainer container, ClientContext context) {
		/* Only deactivate if was deactivated in the first place. 
		 * See the removePendingKey() stack trace: Segment is the listener (getter) ! */
		boolean activated = false;
		if(persistent) {
			activated = container.ext().isActive(segment);
			if(!activated)
				container.activate(segment, 1);
		}
		long ret = segment.getCooldownWakeupByKey(key, container, context);
		if(persistent) {
			if(!activated)
				container.deactivate(segment, 1);
		}
		return ret;
	}

	@Override
	public void requeueAfterCooldown(Key key, long time,
			ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		int blockNum = segment.getBlockNumber(key, container);
		if(blockNum == -1) return;
		reschedule(container, context);
	}

	void reschedule(ObjectContainer container, ClientContext context) {
		if(this.getParentGrabArray() != null) {
			if(logMINOR) Logger.minor(this, "Not rescheduling as already scheduled on "+getParentGrabArray());
			return;
		}
		if(isCancelled(container)) return;
		try {
			getScheduler(container, context).register(null, new SendableGet[] { this }, persistent, container, getContextBlocks(container), true);
		} catch (KeyListenerConstructionException e) {
			Logger.error(this, "Impossible: "+e+" on "+this, e);
		}
	}

	private BlockSet getContextBlocks(ObjectContainer container) {
		FetchContext context = getContext(container);
		BlockSet blocks = context.blocks;
		if(blocks != null) {
			if(persistent) container.activate(blocks, 1);
			return blocks;
		} else return null;
	}

	@Override
	public boolean preRegister(ObjectContainer container, ClientContext context,
			boolean toNetwork) {
		if(!toNetwork) return false;
		if(localRequestOnly(container, context)) {
			if(persistent) container.activate(segment, 1);
			segment.failCheckingDatastore(container, context);
			return true;
		}
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(parent);
			container.activate(parent, 1);
		}
		parent.toNetwork(container, context);
		if(deactivate) container.deactivate(parent, 1);
		return false;
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.priorityClass;
	}

	@Override
	public SendableRequestItem chooseKey(KeysFetchingLocally fetching,
			ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		ArrayList<Integer> possibles = segment.validBlockNumbers(fetching, true, container, context);
		while(true) {
			if(possibles == null || possibles.isEmpty()) return null;
			Integer x = ListUtils.removeRandomBySwapLastSimple(context.random, possibles);
			if(segment.checkRecentlyFailed(x, container, context, fetching, System.currentTimeMillis())) continue;
			return new SplitFileFetcherSegmentSendableRequestItem(x);
		}
	}

	@Override
	public long countAllKeys(ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		return segment.countAllKeys(container, context);
	}

	@Override
	public long countSendableKeys(ObjectContainer container,
			ClientContext context) {
		if(persistent) container.activate(segment, 1);
		return segment.countSendableKeys(container, context);
	}

	@Override
	public boolean isCancelled(ObjectContainer container) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(segment, 1);
		}
		synchronized(segment) {
			return parent.cancelled;
		}
	}

	@Override
	public RequestClient getClient(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.getClient();
	}

	@Override
	public ClientRequester getClientRequest() {
		return parent;
	}

	@Override
	public boolean isSSK() {
		return false;
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(
			PersistentChosenRequest request, RequestScheduler sched, KeysFetchingLocally keys,
			ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		// FIXME why is the fetching keys list not passed in? We could at least check for other fetchers for the same key??? Need to modify the parameters ...
		List<PersistentChosenBlock> blocks = segment.makeBlocks(request, sched, keys, this, container, context);
		if(persistent) container.deactivate(segment, 1);
		return blocks;
	}

	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	@Override
	public long getCooldownTime(ObjectContainer container, ClientContext context, long now) {
		if(persistent) container.activate(segment, 1);
		HasCooldownCacheItem parentRGA = getParentGrabArray();
		long wakeTime = segment.getCooldownTime(container, context, parentRGA, now);
		if(wakeTime > 0)
			context.cooldownTracker.setCachedWakeup(wakeTime, this, parentRGA, persistent, container, context, true);
		return wakeTime;
	}

	@Override
	public void onFailure(BulkCallFailureItem[] items,
			ObjectContainer container, ClientContext context) {
        FetchException[] fetchExceptions = new FetchException[items.length];
        int countFatal = 0;
        for(int i=0;i<items.length;i++) {
        	fetchExceptions[i] = translateException(items[i].e);
        	if(fetchExceptions[i].isFatal()) countFatal++;
        }
        if(persistent) {
        	container.activate(segment, 1);
        	container.activate(parent, 1);
        	container.activate(segment.errors, 1);
        }
        if(parent.isCancelled()) {
                if(logMINOR)
                        Logger.minor(this, "Failing: cancelled");
                // Fail the segment.
                segment.fail(new FetchException(FetchException.CANCELLED), container, context, false);
                // FIXME do we need to free the keyNum's??? Or will that happen later anyway?
                return;
        }
        for(int i=0;i<fetchExceptions.length;i++)
        	segment.errors.inc(fetchExceptions[i].getMode());
        if(persistent)
        	segment.errors.storeTo(container);
        int nonFatalExceptions = items.length - countFatal;
        int[] blockNumbers = new int[nonFatalExceptions];
        if(countFatal > 0) {
        	FetchException[] newFetchExceptions = new FetchException[items.length - countFatal];
        	// Call the fatal callbacks directly.
        	int x = 0;
        	for(int i=0;i<items.length;i++) {
        		int blockNum = ((SplitFileFetcherSegmentSendableRequestItem)items[i].token).blockNum;
        		if(fetchExceptions[i].isFatal()) {
        			segment.onFatalFailure(fetchExceptions[i], blockNum, container, context);
        		} else {
        			blockNumbers[x] = blockNum;
        			newFetchExceptions[x] = fetchExceptions[i];
        			x++;
        		}
        	}
        	fetchExceptions = newFetchExceptions;
        } else {
        	for(int i=0;i<blockNumbers.length;i++)
        		blockNumbers[i] = ((SplitFileFetcherSegmentSendableRequestItem)items[i].token).blockNum;
        }
        if(logMINOR) Logger.minor(this, "Calling segment.onNonFatalFailure with "+blockNumbers.length+" failed fetches");
        segment.onNonFatalFailure(fetchExceptions, blockNumbers, container, context);

        if(persistent) {
        	container.deactivate(segment, 1);
        	container.deactivate(parent, 1);
        	container.deactivate(segment.errors, 1);
        }
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
