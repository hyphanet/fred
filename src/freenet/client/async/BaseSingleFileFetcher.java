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
import freenet.node.NullSendableRequestItem;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableRequestItem;
import freenet.support.Logger;

public abstract class BaseSingleFileFetcher extends SendableGet implements HasKeyListener {

	final ClientKey key;
	protected boolean cancelled;
	protected boolean finished;
	final int maxRetries;
	private int retryCount;
	final FetchContext ctx;
	protected boolean deleteFetchContext;
	static final SendableRequestItem[] keys = new SendableRequestItem[] { NullSendableRequestItem.nullItem };
	/** It is essential that we know when the cooldown will end, otherwise we cannot 
	 * remove the key from the queue if we are killed before that */
	long cooldownWakeupTime;
	
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	
	static {
		Logger.registerClass(BaseManifestPutter.class);
	}

	protected BaseSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent, boolean deleteFetchContext) {
		super(parent);
		this.deleteFetchContext = deleteFetchContext;
		if(logMINOR)
			Logger.minor(this, "Creating BaseSingleFileFetcher for "+key);
		retryCount = 0;
		this.maxRetries = maxRetries;
		this.key = key;
		this.ctx = ctx;
		if(ctx == null) throw new NullPointerException();
		if(key == null) throw new NullPointerException();
		cooldownWakeupTime = -1;
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
		if(fetching.hasKey(key.getNodeKey(false))) return null;
		return keys[0];
	}
	
	@Override
	public boolean hasValidKeys(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		return !fetching.hasKey(key.getNodeKey(false));
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
		retryCount++;
		if(isEmpty(container))
			return false; // Cannot retry e.g. because we got the block and it failed to decode - that's a fatal error.
		if(logMINOR)
			Logger.minor(this, "Attempting to retry... (max "+maxRetries+", current "+retryCount+") on "+this+" finished="+finished+" cancelled="+cancelled);
		// We want 0, 1, ... maxRetries i.e. maxRetries+1 attempts (maxRetries=0 => try once, no retries, maxRetries=1 = original try + 1 retry)
		if((retryCount <= maxRetries) || (maxRetries == -1)) {
			if(persistent)
				container.store(this);
			if(retryCount % RequestScheduler.COOLDOWN_RETRIES == 0) {
				// Add to cooldown queue. Don't reschedule yet.
				long now = System.currentTimeMillis();
				if(cooldownWakeupTime > now) {
					Logger.error(this, "Already on the cooldown queue for "+this+" until "+freenet.support.TimeUtil.formatTime(cooldownWakeupTime - now), new Exception("error"));
					// We must be registered ... unregister
					unregister(container, context, getPriorityClass(container));
				} else {
					if(logMINOR) Logger.minor(this, "Adding to cooldown queue "+this);
					if(persistent)
						container.activate(key, 5);
					RequestScheduler sched = context.getFetchScheduler(key instanceof ClientSSK);
					cooldownWakeupTime = sched.queueCooldown(key, this, container);
					if(persistent)
						container.deactivate(key, 5);
				}
			}
			return true; // We will retry in any case, maybe not just not yet. See requeueAfterCooldown(Key).
		}
		unregister(container, context, getPriorityClass(container));
		return false;
	}

	@Override
	public int getRetryCount() {
		return retryCount;
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
		getScheduler(context).removePendingKeys(this, false);
		super.unregister(container, context, getPriorityClass(container));
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
		try {
			onSuccess(Key.createKeyBlock(this.key, block), false, null, container, context);
		} catch (KeyVerifyException e) {
			Logger.error(this, "onGotKey("+key+","+block+") got "+e+" for "+this, e);
			// FIXME if we get rid of the direct route this must call onFailure()
		}
		if(persistent) {
			container.deactivate(this, 1);
			container.deactivate(this.key, 1);
		}
	}
	
	/** Called when/if the low-level request succeeds. */
	public abstract void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, ObjectContainer container, ClientContext context);
	
	@Override
	public long getCooldownWakeup(Object token, ObjectContainer container, ClientContext context) {
		return cooldownWakeupTime;
	}

	@Override
	public long getCooldownWakeupByKey(Key key, ObjectContainer container, ClientContext context) {
		return cooldownWakeupTime;
	}
	
	@Override
	public synchronized void resetCooldownTimes(ObjectContainer container, ClientContext context) {
		cooldownWakeupTime = -1;
		if(persistent)
			container.store(this);
	}

	@Override
	public void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
		if(cooldownWakeupTime > time) {
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
		if(persistent) {
			container.activate(ctx, 1);
			if(ctx.blocks != null)
				container.activate(ctx.blocks, 5);
		}
		try {
			getScheduler(context).register(this, new SendableGet[] { this }, persistent, container, ctx.blocks, false);
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
			getScheduler(context).register(null, new SendableGet[] { this }, persistent, container, ctx.blocks, true);
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
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		ClientKey ckey = key.cloneKey();
		PersistentChosenBlock block = new PersistentChosenBlock(false, request, keys[0], ckey.getNodeKey(true), ckey, sched);
		return Collections.singletonList(block);
	}

	public KeyListener makeKeyListener(ObjectContainer container, ClientContext context) {
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
		KeyListener ret = new SingleKeyListener(newKey, this, prio, persistent);
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
	
	@Override
	public void preRegister(ObjectContainer container, ClientContext context, boolean toNetwork) {
		if(!toNetwork) return;
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(parent);
			container.activate(parent, 1);
		}
		parent.toNetwork(container, context);
		if(deactivate) container.deactivate(parent, 1);
	}
	
	public synchronized long getCooldownTime(ObjectContainer container, ClientContext context, long now) {
		if(cancelled || finished) return -1;
		if(cooldownWakeupTime < now) return 0;
		return cooldownWakeupTime;
	}

}
