package freenet.client.async;

import java.util.List;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.node.BulkCallFailureItem;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableRequestItem;
import freenet.node.SupportsBulkCallFailure;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

/**
 * A sub-segment of a segment of a splitfile being fetched.
 * Collects together all requests within that segment at a given retry level.
 * Registered on the ClientRequestScheduler instead of SimpleSingleFileFetcher's.
 * When CRS asks it to run a request, returns one, and only unregisters if no more requests in this category.
 * 
 * LOCKING: Synchronize on the parent segment. Nothing else makes sense w.r.t. nested locking.
 * Note that SendableRequest will occasionally lock on (this). That lock is always taken last.
 */
public class SplitFileFetcherSubSegment extends SendableGet implements SupportsBulkCallFailure {

	final int retryCount;
	final SplitFileFetcherSegment segment;
	/**
	 * The block numbers (as Integer's) of the blocks we are currently trying to fetch.
	 * Does not include blocks on the cooldown queue, this is simply used to make 
	 * chooseKey() and allKeys() work / work fast. The retries tables in the Segment are
	 * canonical.
	 */
	final Vector<Integer> blockNums;
	final FetchContext ctx;
	private static boolean logMINOR;
	private boolean cancelled;
	
	@Override
	public boolean isStorageBroken(ObjectContainer container) {
		if(!container.ext().isActive(this))
			throw new IllegalStateException("Must be activated first!");
		if(segment == null) {
			Logger.error(this, "No segment");
			return true;
		}
		if(ctx == null) {
			Logger.error(this, "No fetch context");
			return true;
		}
		if(blockNums == null) {
			Logger.error(this, "No block nums");
			return true;
		}
		return false;
	}
	
	SplitFileFetcherSubSegment(SplitFileFetcherSegment segment, ClientRequester parent, int retryCount) {
		super(parent, false);
		this.segment = segment;
		this.retryCount = retryCount;
		if(parent == null) throw new NullPointerException();
		ctx = segment.blockFetchContext;
		blockNums = new Vector<Integer>();
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	}
	
	@Override
	public FetchContext getContext(ObjectContainer container) {
		if(persistent) container.activate(ctx, 1);
		return ctx;
	}

	@Override
	public SendableRequestItem chooseKey(KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		return null;
	}
	
	@Override
	public ClientKey getKey(Object token, ObjectContainer container) {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * Fetch the array from the segment because we need to include *ALL* keys, especially
	 * those on cooldown queues. This is important when unregistering.
	 */
	@Override
	public long countAllKeys(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(segment, 1);
		}
		// j16sdiz (22-DEC-2008):
		// ClientRequestSchedular.removePendingKeys() call this to get a list of request to be removed
		// FIXME ClientRequestSchedular.removePendingKeys() is leaking, what's missing here?
		return segment.getKeyNumbersAtRetryLevel(retryCount, container, context).length;
	}
	
	/**
	 * Just those keys which are eligible to be started now.
	 */
	@Override
	public long countSendableKeys(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(blockNums, 1);
		}
		cleanBlockNums(container);
		return blockNums.size();
	}
	
	private void cleanBlockNums(ObjectContainer container) {
		synchronized(segment) {
			int initSize = blockNums.size();
			Integer prev = null;
			for(int i=0;i<blockNums.size();i++) {
				Integer x = blockNums.get(i);
				if(x == prev || x.equals(prev)) {
					blockNums.remove(i);
					if(logMINOR) Logger.minor(this, "Removing "+x+" (index "+i+") in cleanBlockNums on "+this);
					i--;
					if(persistent) container.delete(x);
				} else prev = x;
			}
			if(blockNums.size() < initSize) {
				Logger.error(this, "Cleaned block number list duplicates: was "+initSize+" now "+blockNums.size());
			}
		}
	}

	// SendableGet has a hashCode() and inherits equals(), which is consistent with the hashCode().
	
	@Override
	public void onFailure(BulkCallFailureItem[] items, ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
	}
	
	// Translate it, then call the real onFailure
	@Override
	public void onFailure(LowLevelGetException e, Object token, ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
	}

	// Real onFailure
	protected void onFailure(FetchException e, Object token, ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
	}
	
	protected void onSuccess(Bucket data, boolean fromStore, Integer token, int blockNo, ClientCHKBlock block, ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
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
	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.priorityClass;
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
	
	public boolean isEmpty(ObjectContainer container) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(blockNums, 1);
		}
		synchronized(segment) {
			if(blockNums.isEmpty() && (!cancelled) && logMINOR)
				Logger.minor(this, "Subsegment is empty, removing: "+this);
			return cancelled || blockNums.isEmpty();
		}
	}

	@Override
	public boolean isSSK() {
		// Not allowed in splitfiles
		return false;
	}
	
	public void onGotKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Terminate a subsegment. Called by the segment, which will have already removed the
	 * subsegment from the list. Will delete the object from the database if persistent.
	 */
	public void kill(ObjectContainer container, ClientContext context, boolean dontDeactivateSeg, boolean cancelledAlready) {
		if(persistent) {
			container.activate(segment, 1);
			container.activate(blockNums, 1);
		}
		if(logMINOR)
			Logger.minor(this, "Killing "+this);
		// Do unregister() first so can get and unregister each key and avoid a memory leak
		unregister(container, context, getPriorityClass(container));
		Integer[] oldNums = null;
		synchronized(segment) {
			if(cancelledAlready) {
				if(!cancelled)
					cancelled = true;
			} else {
				if(cancelled) return;
				cancelled = true;
			}
			if(persistent)
				oldNums = blockNums.toArray(new Integer[blockNums.size()]);
			blockNums.clear();
		}
		if(persistent && oldNums != null && oldNums.length > 0) {
			for(Integer i : oldNums) container.delete(i);
		}
		if(persistent) removeFrom(container, context, dontDeactivateSeg);
	}
	
	public void removeFrom(ObjectContainer container, ClientContext context, boolean dontDeactivateSeg) {
		container.activate(segment, 1);
		container.activate(blockNums, 1);
		synchronized(segment) {
			if(!cancelled) {
				Logger.error(this, "Removing when not cancelled! on "+this, new Exception("error"));
				cancelled = true;
			}
			if(!blockNums.isEmpty()) {
				Logger.error(this, "Removing when blockNums not empty! on "+this, new Exception("error"));
				for(Integer i : blockNums) container.delete(i);
				blockNums.clear();
			}
		}
		container.delete(blockNums);
		container.delete(this);
		if(!dontDeactivateSeg)
			container.deactivate(segment, 1);
		// We do not need to call SendableGet as it has no internal data structures that need deleting.
	}

	@Override
	public long getCooldownWakeup(Object token, ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
		// We must complete this immediately or risk data loss.
		// We are not being called by RGA so there is no problem.
		migrateToSegmentFetcher(container, context);
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

	public void reschedule(ObjectContainer container, ClientContext context) {
		// Don't schedule self, schedule segment fetcher.
		migrateToSegmentFetcher(container, context);
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		// This is safe because it won't be removed from the RGA.
		queueMigrateToSegmentFetcher(container, context);
		return null;
	}

	private void queueMigrateToSegmentFetcher(ObjectContainer container,
			ClientContext context) {
		assert(container != null);
		assert(persistent);
		if(!container.ext().isStored(this)) return;
		if(logMINOR) Logger.minor(this, "Queueing migrate to segment fetcher for "+this);
		try {
			context.jobRunner.queue(new DBJob() {
				
				@Override
				public boolean run(ObjectContainer container, ClientContext context) {
					if(!container.ext().isStored(SplitFileFetcherSubSegment.this))
						return false; // Already migrated
					container.activate(SplitFileFetcherSubSegment.this, 1);
					migrateToSegmentFetcher(container, context);
					return false;
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} catch (DatabaseDisabledException e) {
			// Ignore
		}
	}

	private void migrateToSegmentFetcher(ObjectContainer container,
			ClientContext context) {
		if(segment == null) {
			Logger.error(this, "Migrating to segment fetcher on "+this+" but segment is null!");
			if(container.ext().isStored(this))
				Logger.error(this, "... and this is stored!");
			if(container.ext().isActive(this))
				Logger.error(this, "... and activated!");
			return;
		}
		boolean segmentActive = true;
		if(persistent) {
			segmentActive = container.ext().isActive(segment);
			if(!segmentActive) container.activate(segment, 1);
		}
		boolean cancelled = isCancelled(container) || isEmpty(container) || segment.isFinishing(container);
		if(!cancelled) {
			SplitFileFetcherSegmentGet getter = segment.makeGetter(container, context);
			if(persistent) container.activate(getter, 1);
			getter.reschedule(container, context);
			if(persistent) container.deactivate(getter, 1);
		} else {
			kill(container, context, true, false);
		}
		if(!segmentActive) container.deactivate(segment, 1);
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

	public int objectHash() {
		return super.hashCode();
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		if(blockNums == null)
			throw new NullPointerException("Storing "+this+" but blockNums == null!");
		if(segment == null)
			throw new NullPointerException("Storing "+this+" but segment == null!");
		return true;
	}
	
	public boolean objectCanUpdate(ObjectContainer container) {
		if(blockNums == null) {
			if(!container.ext().isActive(this)) {
				Logger.error(this, "Not active and blockNums == null but trying to store", new Exception("error"));
				return false;
			}
			throw new NullPointerException("Storing "+this+" but blockNums == null!");
		}
		if(segment == null)
			throw new NullPointerException("Storing "+this+" but segment == null!");
		return true;
	}
	
	@Override
	public boolean preRegister(ObjectContainer container, ClientContext context, boolean toNetwork) {
		if(!toNetwork) return false;
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
	public long getCooldownTime(ObjectContainer container, ClientContext context, long now) {
		queueMigrateToSegmentFetcher(container, context);
		return Long.MAX_VALUE;
	}

}
