/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientSSK;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.KeysFetchingLocally;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.support.Logger;

public abstract class BaseSingleFileFetcher extends SendableGet implements GotKeyListener {

	final ClientKey key;
	protected boolean cancelled;
	protected boolean finished;
	final int maxRetries;
	private int retryCount;
	final FetchContext ctx;
	static final Object[] keys = new Object[] { new Integer(0) };
	/** It is essential that we know when the cooldown will end, otherwise we cannot 
	 * remove the key from the queue if we are killed before that */
	long cooldownWakeupTime;
	private boolean chosen;

	protected BaseSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent) {
		super(parent);
		retryCount = 0;
		this.maxRetries = maxRetries;
		this.key = key;
		this.ctx = ctx;
		cooldownWakeupTime = -1;
	}

	public Object[] allKeys(ObjectContainer container) {
		return keys;
	}
	
	public Object[] sendableKeys(ObjectContainer container) {
		return keys;
	}
	
	public Object chooseKey(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		if(fetching.hasKey(key.getNodeKey())) return null;
		if(chosen) return null;
		chosen = true;
		if(persistent)
			container.set(this);
		return keys[0];
	}
	
	public boolean hasValidKeys(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		if(chosen) return false;
		return !fetching.hasKey(key.getNodeKey());
	}
	
	public ClientKey getKey(Object token, ObjectContainer container) {
		if(persistent)
			container.activate(key, 5);
		return key;
	}
	
	public FetchContext getContext() {
		return ctx;
	}

	public boolean isSSK() {
		return key instanceof ClientSSK;
	}

	/** Try again - returns true if we can retry 
	 * @param sched */
	protected boolean retry(ObjectContainer container, ClientContext context) {
		retryCount++;
		chosen = false;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Attempting to retry... (max "+maxRetries+", current "+retryCount+ ')');
		// We want 0, 1, ... maxRetries i.e. maxRetries+1 attempts (maxRetries=0 => try once, no retries, maxRetries=1 = original try + 1 retry)
		if((retryCount <= maxRetries) || (maxRetries == -1)) {
			if(persistent)
				container.set(this);
			if(retryCount % ClientRequestScheduler.COOLDOWN_RETRIES == 0) {
				// Add to cooldown queue. Don't reschedule yet.
				long now = System.currentTimeMillis();
				if(cooldownWakeupTime > now)
					Logger.error(this, "Already on the cooldown queue for "+this, new Exception("error"));
				else {
					RequestScheduler sched = context.getFetchScheduler(key instanceof ClientSSK);
					cooldownWakeupTime = sched.queueCooldown(key, this);
				}
				return true; // We will retry, just not yet. See requeueAfterCooldown(Key).
			} else {
				schedule(container, context, false);
			}
			return true;
		}
		return false;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public ClientRequester getClientRequest() {
		return parent;
	}

	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1); // Not much point deactivating it
		short retval = parent.getPriorityClass();
		return retval;
	}

	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			cancelled = true;
		}
		if(persistent) {
			container.set(this);
			container.activate(key, 5);
		}
		
		unregisterAll(container, context);
	}
	
	/**
	 * Remove the pendingKeys item and then remove from the queue as well.
	 * Call unregister(container) if you only want to remove from the queue.
	 */
	public void unregisterAll(ObjectContainer container, ClientContext context) {
		getScheduler(context).removePendingKey(this, false, key.getNodeKey(), container);
		super.unregister(container);
	}

	public synchronized boolean isCancelled(ObjectContainer container) {
		return cancelled;
	}
	
	public synchronized boolean isEmpty(ObjectContainer container) {
		return cancelled || finished || chosen;
	}
	
	public RequestClient getClient() {
		return parent.getClient();
	}

	public boolean dontCache(ObjectContainer container) {
		return !ctx.cacheLocalRequests;
	}
	
	public boolean dontCache() {
		return !ctx.cacheLocalRequests;
	}
	
	public boolean canRemove(ObjectContainer container) {
		// Simple request, once it's sent, it's sent. May be requeued at a different # retries.
		return true;
	}

	public void onGotKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(key, 5);
			container.activate(this.key, 5);
		}
		synchronized(this) {
			chosen = true;
			finished = true;
			if(persistent)
				container.set(this);
			if(isCancelled(container)) return;
			if(!key.equals(this.key.getNodeKey())) {
				Logger.normal(this, "Got sent key "+key+" but want "+this.key+" for "+this);
				return;
			}
		}
		unregister(container); // Key has already been removed from pendingKeys
		try {
			onSuccess(Key.createKeyBlock(this.key, block), false, null, container, context);
		} catch (KeyVerifyException e) {
			Logger.error(this, "onGotKey("+key+","+block+") got "+e+" for "+this, e);
			// FIXME if we get rid of the direct route this must call onFailure()
		}
		if(persistent)
			container.deactivate(this, 1);
	}
	

	public long getCooldownWakeup(Object token, ObjectContainer container) {
		return cooldownWakeupTime;
	}
	
	public long getCooldownWakeupByKey(Key key, ObjectContainer container) {
		return cooldownWakeupTime;
	}
	
	public synchronized void resetCooldownTimes(ObjectContainer container) {
		cooldownWakeupTime = -1;
		if(persistent)
			container.set(this);
	}
	
	public void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
		if(cooldownWakeupTime > time) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Not requeueing as deadline has not passed yet");
			return;
		}
		if(persistent)
			container.activate(this.key, 5);
		if(!(key.equals(this.key.getNodeKey()))) {
			Logger.error(this, "Got requeueAfterCooldown for wrong key: "+key+" but mine is "+this.key.getNodeKey()+" for "+this.key);
			return;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Requeueing after cooldown "+key+" for "+this);
		schedule(container, context, false);
	}

	public void schedule(ObjectContainer container, ClientContext context, boolean delayed) {
		getScheduler(context).register(this, new SendableGet[] { this }, delayed, persistent, true, ctx.blocks, null);
	}
	
	public SendableGet getRequest(Key key, ObjectContainer container) {
		return this;
	}

	public Key[] listKeys(ObjectContainer container) {
		if(cancelled || finished)
			return new Key[0];
		else {
			if(persistent)
				container.activate(key, 5);
			return new Key[] { key.getNodeKey() };
		}
	}

}
