package freenet.client.async;

import java.util.ArrayList;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableRequestItem;
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
public class SplitFileFetcherSegmentGet extends SendableGet {
	
	public SplitFileFetcherSegmentGet(ClientRequester parent, SplitFileFetcherSegment segment) {
		super(parent);
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
			if(Logger.shouldLog(LogLevel.MINOR, this)) 
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
			segment.onFatalFailure(e, ((SplitFileFetcherSegmentSendableRequestItem)token).blockNum, null, container, context);
		} else {
			segment.onNonFatalFailure(e, ((SplitFileFetcherSegmentSendableRequestItem)token).blockNum, null, container, context);
		}
		if(persistent) {
			container.deactivate(segment, 1);
			container.deactivate(parent, 1);
			container.deactivate(segment.errors, 1);
		}
	}

	@Override
	public long getCooldownWakeup(Object token, ObjectContainer container) {
		if(persistent) container.activate(segment, 1);
		return segment.getCooldownWakeup(((SplitFileFetcherSegmentSendableRequestItem)token).blockNum);
	}

	@Override
	public long getCooldownWakeupByKey(Key key, ObjectContainer container) {
		/* Only deactivate if was deactivated in the first place. 
		 * See the removePendingKey() stack trace: Segment is the listener (getter) ! */
		boolean activated = false;
		if(persistent) {
			activated = container.ext().isActive(segment);
			if(!activated)
				container.activate(segment, 1);
		}
		long ret = segment.getCooldownWakeupByKey(key, container);
		if(persistent) {
			if(!activated)
				container.deactivate(segment, 1);
		}
		return ret;
	}

	@Override
	public void resetCooldownTimes(ObjectContainer container) {
		if(persistent) container.activate(segment, 1);
		segment.resetCooldownTimes(container);
	}

	@Override
	public void requeueAfterCooldown(Key key, long time,
			ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		int blockNum = segment.getBlockNumber(key, container);
		if(blockNum == -1) return;
		reschedule(container, context);
	}

	private void reschedule(ObjectContainer container, ClientContext context) {
		if(this.getParentGrabArray() != null) {
			if(logMINOR) Logger.minor(this, "Not rescheduling as already scheduled on "+getParentGrabArray());
			return;
		}
		try {
			getScheduler(context).register(null, new SendableGet[] { this }, persistent, container, getContextBlocks(container), true);
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
	public boolean hasValidKeys(KeysFetchingLocally fetching,
			ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		return segment.hasValidKeys(this, fetching, container, context);
	}

	@Override
	public void preRegister(ObjectContainer container, ClientContext context,
			boolean toNetwork) {
		if(!toNetwork) return;
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(parent);
			container.activate(parent, 1);
		}
		parent.toNetwork(container, context);
		if(deactivate) container.deactivate(parent, 1);
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.priorityClass;
	}

	@Override
	public int getRetryCount() {
		// Retry count no longer involved in scheduling.
		// Different blocks may have different retry counts.
		// FIXME remove??? compute the lowest/highest/average?
		return 0;
	}

	@Override
	public SendableRequestItem chooseKey(KeysFetchingLocally keys,
			ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		ArrayList<Integer> possibles = segment.validBlockNumbers(keys, true, container, context);
		if(possibles == null || possibles.isEmpty()) return null;
		return new SplitFileFetcherSegmentSendableRequestItem(possibles.get(context.random.nextInt(possibles.size())));
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
			PersistentChosenRequest request, RequestScheduler sched,
			ObjectContainer container, ClientContext context) {
		if(persistent) container.activate(segment, 1);
		// FIXME why is the fetching keys list not passed in? We could at least check for other fetchers for the same key??? Need to modify the parameters ...
		List<PersistentChosenBlock> blocks = segment.makeBlocks(request, sched, container, context);
		if(persistent) container.deactivate(segment, 1);
		return blocks;
	}

}
