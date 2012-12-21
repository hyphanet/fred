/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.Collections;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.NullSendableRequestItem;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableRequestItem;
import freenet.support.Logger;
import freenet.support.TimeUtil;

public abstract class BaseSingleFileFetcher extends SendableGet implements HasKeyListener, HasCooldownTrackerItem {

	public static class MyCooldownTrackerItem implements CooldownTrackerItem {

		public int retryCount;
		public long cooldownWakeupTime;

	}

	final ClientKey key;
	protected boolean cancelled;
	protected boolean finished;
	final int maxRetries;
	private int retryCount;
	final FetchContext ctx;
	protected boolean deleteFetchContext;
	static final SendableRequestItem[] keys = new SendableRequestItem[] { NullSendableRequestItem.nullItem };
	private int cachedCooldownTries;
	private long cachedCooldownTime;
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(BaseSingleFileFetcher.class);
	}

	protected BaseSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent, boolean deleteFetchContext, boolean realTimeFlag) {
		super(parent, realTimeFlag);
		this.deleteFetchContext = deleteFetchContext;
		if(logMINOR)
			Logger.minor(this, "Creating BaseSingleFileFetcher for "+key);
		retryCount = 0;
		this.maxRetries = maxRetries;
		this.key = key;
		this.ctx = ctx;
		if(ctx == null) throw new NullPointerException();
	}

	@Override
	public long countAllKeys(ObjectContainer container, ClientContext context) {
		return 1;
	}
	
	@Override
	public long countSendableKeys(ObjectContainer container, ClientContext context) {
		return 1;
	}
	
	@Override
	public SendableRequestItem chooseKey(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		Key k = key.getNodeKey(false);
		if(fetching.hasKey(k, this, persistent, container)) return null;
		long l = fetching.checkRecentlyFailed(k, realTimeFlag);
		long now = System.currentTimeMillis();
		if(l > 0 && l > now) {
			if(maxRetries == -1 || (maxRetries >= RequestScheduler.COOLDOWN_RETRIES)) {
				// FIXME synchronization!!!
				if(logMINOR) Logger.minor(this, "RecentlyFailed -> cooldown until "+TimeUtil.formatTime(l-now)+" on "+this);
				MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
				tracker.cooldownWakeupTime = Math.max(tracker.cooldownWakeupTime, l);
				return null;
			} else {
				this.onFailure(new LowLevelGetException(LowLevelGetException.RECENTLY_FAILED), null, container, context);
				return null;
			}
		}
		return keys[0];
	}
	
	@Override
	public ClientKey getKey(Object token, ObjectContainer container) {
		if(persistent)
			container.activate(key, 5);
		return key;
	}
	
	@Override
	public FetchContext getContext(ObjectContainer container) {
		if(persistent) container.activate(ctx, 1);
		return ctx;
	}

	@Override
	public boolean isSSK() {
		return key instanceof ClientSSK;
	}

	/** Try again - returns true if we can retry */
	protected boolean retry(ObjectContainer container, ClientContext context) {
		if(isEmpty(container)) {
			if(logMINOR) Logger.minor(this, "Not retrying because empty");
			return false; // Cannot retry e.g. because we got the block and it failed to decode - that's a fatal error.
		}
		// We want 0, 1, ... maxRetries i.e. maxRetries+1 attempts (maxRetries=0 => try once, no retries, maxRetries=1 = original try + 1 retry)
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		int r;
		if(maxRetries == -1)
			r = ++tracker.retryCount;
		else
			r = ++retryCount;
		if(logMINOR)
			Logger.minor(this, "Attempting to retry... (max "+maxRetries+", current "+r+") on "+this+" finished="+finished+" cancelled="+cancelled);
		if((r <= maxRetries) || (maxRetries == -1)) {
			if(persistent && maxRetries != -1)
				container.store(this);
			checkCachedCooldownData(container);
			if(cachedCooldownTries == 0 || r % cachedCooldownTries == 0) {
				// Add to cooldown queue. Don't reschedule yet.
				long now = System.currentTimeMillis();
				if(tracker.cooldownWakeupTime > now) {
					Logger.error(this, "Already on the cooldown queue for "+this+" until "+freenet.support.TimeUtil.formatTime(tracker.cooldownWakeupTime - now), new Exception("error"));
				} else {
					if(logMINOR) Logger.minor(this, "Adding to cooldown queue "+this);
					if(persistent)
						container.activate(key, 5);
					tracker.cooldownWakeupTime = now + cachedCooldownTime;
					context.cooldownTracker.setCachedWakeup(tracker.cooldownWakeupTime, this, getParentGrabArray(), persistent, container, context, true);
					if(logMINOR) Logger.minor(this, "Added single file fetcher into cooldown until "+TimeUtil.formatTime(tracker.cooldownWakeupTime - now));
					if(persistent)
						container.deactivate(key, 5);
				}
				onEnterFiniteCooldown(context);
			} else {
				// Wake the CRS after clearing cache.
				this.clearCooldown(container, context, true);
			}
			return true; // We will retry in any case, maybe not just not yet. See requeueAfterCooldown(Key).
		}
		unregister(container, context, getPriorityClass(container));
		return false;
	}

	private void checkCachedCooldownData(ObjectContainer container) {
		// 0/0 is illegal, and it's also the default, so use it to indicate we haven't fetched them.
		if(!(cachedCooldownTime == 0 && cachedCooldownTries == 0)) {
			// Okay, we have already got them.
			return;
		}
		innerCheckCachedCooldownData(container);
	}
	
	private void innerCheckCachedCooldownData(ObjectContainer container) {
		boolean active = true;
		if(persistent) {
			active = container.ext().isActive(ctx);
			container.activate(ctx, 1);
		}
		cachedCooldownTries = ctx.getCooldownRetries();
		cachedCooldownTime = ctx.getCooldownTime();
		if(!active) container.deactivate(ctx, 1);
	}

	protected void onEnterFiniteCooldown(ClientContext context) {
		// Do nothing.
	}

	private MyCooldownTrackerItem makeCooldownTrackerItem(
			ObjectContainer container, ClientContext context) {
		return (MyCooldownTrackerItem) context.cooldownTracker.make(this, persistent, container);
	}

	@Override
	public CooldownTrackerItem makeCooldownTrackerItem() {
		return new MyCooldownTrackerItem();
	}
	
	@Override
	public ClientRequester getClientRequest() {
		return parent;
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1); // Not much point deactivating it
		short retval = parent.getPriorityClass();
		return retval;
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			cancelled = true;
		}
		if(persistent) {
			container.store(this);
			container.activate(key, 5);
		}
		
		unregisterAll(container, context);
	}
	
	/**
	 * Remove the pendingKeys item and then remove from the queue as well.
	 * Call unregister(container) if you only want to remove from the queue.
	 */
	public void unregisterAll(ObjectContainer container, ClientContext context) {
		getScheduler(container, context).removePendingKeys(this, false);
		unregister(container, context, (short)-1);
	}

	@Override
	public void unregister(ObjectContainer container, ClientContext context, short oldPrio) {
		context.cooldownTracker.remove(this, persistent, container);
		super.unregister(container, context, oldPrio);
	}

	@Override
	public synchronized boolean isCancelled(ObjectContainer container) {
		return cancelled;
	}
	
	public synchronized boolean isEmpty(ObjectContainer container) {
		return cancelled || finished;
	}
	
	@Override
	public RequestClient getClient(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.getClient();
	}

	public void onGotKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(key, 5);
			container.activate(this.key, 5);
		}
		synchronized(this) {
			if(finished) {
				if(logMINOR)
					Logger.minor(this, "onGotKey() called twice on "+this, new Exception("debug"));
				return;
			}
			finished = true;
			if(persistent)
				container.store(this);
			if(isCancelled(container)) return;
			if(key == null)
				throw new NullPointerException();
			if(this.key == null)
				throw new NullPointerException("Key is null on "+this);
			if(!key.equals(this.key.getNodeKey(false))) {
				Logger.normal(this, "Got sent key "+key+" but want "+this.key+" for "+this);
				return;
			}
		}
		unregister(container, context, getPriorityClass(container)); // Key has already been removed from pendingKeys
		onSuccess(block, false, null, container, context);
		if(persistent) {
			container.deactivate(this, 1);
			container.deactivate(this.key, 1);
		}
	}
	
	public void onSuccess(KeyBlock lowLevelBlock, boolean fromStore, Object token, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(key, Integer.MAX_VALUE);
		}
		ClientKeyBlock block;
		try {
			block = Key.createKeyBlock(this.key, lowLevelBlock);
			onSuccess(block, fromStore, token, container, context);
		} catch (KeyVerifyException e) {
			onBlockDecodeError(token, container, context);
		}
	}
	
	protected abstract void onBlockDecodeError(Object token, ObjectContainer container,
			ClientContext context);

	/** Called when/if the low-level request succeeds. */
	public abstract void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, ObjectContainer container, ClientContext context);
	
	@Override
	public long getCooldownWakeup(Object token, ObjectContainer container, ClientContext context) {
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		return tracker.cooldownWakeupTime;
	}

	@Override
	public long getCooldownWakeupByKey(Key key, ObjectContainer container, ClientContext context) {
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		return tracker.cooldownWakeupTime;
	}
	
	@Override
	public void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		if(tracker.cooldownWakeupTime > time) {
			if(logMINOR) Logger.minor(this, "Not requeueing as deadline has not passed yet");
			return;
		}
		if(isEmpty(container)) {
			if(logMINOR) Logger.minor(this, "Not requeueing as cancelled or finished");
			return;
		}
		if(persistent)
			container.activate(this.key, 5);
		if(!(key.equals(this.key.getNodeKey(false)))) {
			Logger.error(this, "Got requeueAfterCooldown for wrong key: "+key+" but mine is "+this.key.getNodeKey(false)+" for "+this.key);
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Requeueing after cooldown "+key+" for "+this);
		reschedule(container, context);
		if(persistent)
			container.deactivate(this.key, 5);
	}

	public void schedule(ObjectContainer container, ClientContext context) {
		if(key == null) throw new NullPointerException();
		if(persistent) {
			container.activate(ctx, 1);
			if(ctx.blocks != null)
				container.activate(ctx.blocks, 5);
		}
		try {
			getScheduler(container, context).register(this, new SendableGet[] { this }, persistent, container, ctx.blocks, false);
		} catch (KeyListenerConstructionException e) {
			Logger.error(this, "Impossible: "+e+" on "+this, e);
		}
	}
	
	public void reschedule(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(ctx, 1);
			if(ctx.blocks != null)
				container.activate(ctx.blocks, 5);
		}
		try {
			getScheduler(container, context).register(null, new SendableGet[] { this }, persistent, container, ctx.blocks, true);
		} catch (KeyListenerConstructionException e) {
			Logger.error(this, "Impossible: "+e+" on "+this, e);
		}
	}
	
	public SendableGet getRequest(Key key, ObjectContainer container) {
		return this;
	}

	@Override
	public Key[] listKeys(ObjectContainer container) {
		if(container != null && !persistent)
			Logger.error(this, "listKeys() on "+this+" but persistent=false, stored is "+container.ext().isStored(this)+" active is "+container.ext().isActive(this));
		synchronized(this) {
			if(cancelled || finished)
				return new Key[0];
		}
		if(persistent)
			container.activate(key, 5);
		return new Key[] { key.getNodeKey(true) };
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, KeysFetchingLocally keysFetching, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		ClientKey ckey = key.cloneKey();
		Key k = ckey.getNodeKey(true);
		if(keysFetching.hasKey(k, this, persistent, container))
			return null;
		long l = keysFetching.checkRecentlyFailed(k, realTimeFlag);
		long now = System.currentTimeMillis();
		if(l > 0 && l > now) {
			if(maxRetries == -1 || (maxRetries >= RequestScheduler.COOLDOWN_RETRIES)) {
				// FIXME synchronization!!!
				if(logMINOR) Logger.minor(this, "RecentlyFailed -> cooldown until "+TimeUtil.formatTime(l-now)+" on "+this);
				MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
				tracker.cooldownWakeupTime = Math.max(tracker.cooldownWakeupTime, l);
				return null;
			} else {
				this.onFailure(new LowLevelGetException(LowLevelGetException.RECENTLY_FAILED), null, container, context);
				return null;
			}
		}
		PersistentChosenBlock block = new PersistentChosenBlock(false, request, keys[0], k, ckey, sched);
		return Collections.singletonList(block);
	}

	@Override
	public KeyListener makeKeyListener(ObjectContainer container, ClientContext context, boolean onStartup) {
		if(persistent) {
			container.activate(key, 5);
			container.activate(parent, 1);
			container.activate(ctx, 1);
		}
		synchronized(this) {
			if(finished) return null;
			if(cancelled) return null;
		}
		if(key == null) {
			Logger.error(this, "Key is null - left over BSSF? on "+this+" in makeKeyListener()", new Exception("error"));
			if(persistent) container.delete(this);
			return null;
		}
		Key newKey = key.getNodeKey(true);
		if(parent == null) {
			Logger.error(this, "Parent is null on "+this+" persistent="+persistent+" key="+key+" ctx="+ctx);
			if(container != null)
				Logger.error(this, "Stored = "+container.ext().isStored(this)+" active = "+container.ext().isActive(this));
			return null;
		}
		short prio = parent.getPriorityClass();
		KeyListener ret = new SingleKeyListener(newKey, this, prio, persistent, realTimeFlag);
		if(persistent) {
			container.deactivate(key, 5);
			container.deactivate(parent, 1);
			container.deactivate(ctx, 1);
		}
		return ret;
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		super.removeFrom(container, context);
		if(deleteFetchContext) {
			container.activate(ctx, 1);
			ctx.removeFrom(container);
		}
		container.activate(key, 5);
		key.removeFrom(container);
	}
	
	protected abstract void notFoundInStore(ObjectContainer container, ClientContext context);
	
	@Override
	public boolean preRegister(ObjectContainer container, ClientContext context, boolean toNetwork) {
		if(!toNetwork) return false;
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(ctx);
			container.activate(ctx, 1);
		}
		boolean localOnly = ctx.localRequestOnly;
		if(deactivate) container.deactivate(ctx, 1);
		if(localOnly) {
			notFoundInStore(container, context);
			return true;
		}
		deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(parent);
			container.activate(parent, 1);
		}
		parent.toNetwork(container, context);
		if(deactivate) container.deactivate(parent, 1);
		return false;
	}
	
	@Override
	public synchronized long getCooldownTime(ObjectContainer container, ClientContext context, long now) {
		if(cancelled || finished) return -1;
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(container, context);
		long wakeTime = tracker.cooldownWakeupTime;
		if(wakeTime <= now)
			tracker.cooldownWakeupTime = wakeTime = 0;
		KeysFetchingLocally fetching = getScheduler(container, context).fetchingKeys();
		if(wakeTime <= 0 && fetching.hasKey(getNodeKey(null, container), this, cancelled, container)) {
			wakeTime = Long.MAX_VALUE;
			// tracker.cooldownWakeupTime is only set for a real cooldown period, NOT when we go into hierarchical cooldown because the request is already running.
		}
		if(wakeTime == 0)
			return 0;
		HasCooldownCacheItem parentRGA = getParentGrabArray();
		context.cooldownTracker.setCachedWakeup(wakeTime, this, parentRGA, persistent, container, context, true);
		return wakeTime;
	}
	
	/** Reread the cached cooldown values (and anything else) from the FetchContext
	 * after it changes. FIXME: Ideally this should be a generic mechanism, but
	 * that looks too complex without significant changes to data structures.
	 * For now it's just a hack to make changing the polling interval in USKs work.
	 * See bug https://bugs.freenetproject.org/view.php?id=4984
	 * @param container The database if this is a persistent request.
	 * @param context The context object.
	 */
	public void onChangedFetchContext(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(cancelled || finished) return;
		}
		innerCheckCachedCooldownData(container);
	}

}
