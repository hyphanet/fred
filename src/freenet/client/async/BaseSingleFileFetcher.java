/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientSSK;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.KeysFetchingLocally;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.support.Executor;
import freenet.support.Logger;

public abstract class BaseSingleFileFetcher extends SendableGet {

	final ClientKey key;
	protected boolean cancelled;
	final int maxRetries;
	private int retryCount;
	final FetchContext ctx;
	static final Object[] keys = new Object[] { Integer.valueOf(0) };
	/** It is essential that we know when the cooldown will end, otherwise we cannot 
	 * remove the key from the queue if we are killed before that */
	long cooldownWakeupTime;

	protected BaseSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent) {
		super(parent);
		retryCount = 0;
		this.maxRetries = maxRetries;
		this.key = key;
		this.ctx = ctx;
		cooldownWakeupTime = -1;
	}

	@Override
	public Object[] allKeys() {
		return keys;
	}
	
	@Override
	public Object[] sendableKeys() {
		return keys;
	}
	
	@Override
	public Object chooseKey(KeysFetchingLocally fetching) {
		if(fetching.hasKey(key.getNodeKey())) return null;
		return keys[0];
	}
	
	@Override
	public boolean hasValidKeys(KeysFetchingLocally fetching) {
		return !fetching.hasKey(key.getNodeKey());
	}
	
	@Override
	public ClientKey getKey(Object token) {
		return key;
	}
	
	@Override
	public FetchContext getContext() {
		return ctx;
	}

	@Override
	public boolean isSSK() {
		return key instanceof ClientSSK;
	}

	/**
	 * Try again - returns true if we can retry 
	 * @param sched
	 * @param the executor we will use to run the retry off-thread
	 */
	protected boolean retry(RequestScheduler sched, Executor exec) {
		retryCount++;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Attempting to retry... (max "+maxRetries+", current "+retryCount+ ')');
		// We want 0, 1, ... maxRetries i.e. maxRetries+1 attempts (maxRetries=0 => try once, no retries, maxRetries=1 = original try + 1 retry)
		if((retryCount <= maxRetries) || (maxRetries == -1)) {
			if(retryCount % ClientRequestScheduler.COOLDOWN_RETRIES == 0) {
				// Add to cooldown queue. Don't reschedule yet.
				long now = System.currentTimeMillis();
				if(cooldownWakeupTime > now)
					Logger.error(this, "Already on the cooldown queue for "+this, new Exception("error"));
				else
				cooldownWakeupTime = sched.queueCooldown(key, this);
			} else {
				exec.execute(new Runnable() {
					public void run() {
				schedule();
			}
				}, "Retry executor for "+sched.toString());
		}
			return true; // We will retry in any case, maybe not just not yet. See requeueAfterCooldown(Key).
		}
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
	public short getPriorityClass() {
		return parent.getPriorityClass();
	}

	@Override
	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	public void cancel() {
		synchronized(this) {
			cancelled = true;
		}
		super.unregister(false);
	}

	@Override
	public synchronized boolean isCancelled() {
		return cancelled;
	}
	
	public synchronized boolean isEmpty() {
		return cancelled;
	}
	
	@Override
	public Object getClient() {
		return parent.getClient();
	}

	@Override
	public boolean dontCache() {
		return !ctx.cacheLocalRequests;
	}
	
	public boolean canRemove() {
		// Simple request, once it's sent, it's sent. May be requeued at a different # retries.
		return true;
	}

	@Override
	public void onGotKey(Key key, KeyBlock block, RequestScheduler sched) {
		synchronized(this) {
			if(isCancelled()) return;
			if(!key.equals(this.key.getNodeKey())) {
				Logger.normal(this, "Got sent key "+key+" but want "+this.key+" for "+this);
				return;
			}
		}
		try {
			onSuccess(Key.createKeyBlock(this.key, block), false, null, sched);
		} catch (KeyVerifyException e) {
			Logger.error(this, "onGotKey("+key+","+block+") got "+e+" for "+this, e);
			// FIXME if we get rid of the direct route this must call onFailure()
		}
	}
	

	@Override
	public long getCooldownWakeup(Object token) {
		return cooldownWakeupTime;
	}
	
	@Override
	public long getCooldownWakeupByKey(Key key) {
		return cooldownWakeupTime;
	}
	
	@Override
	public synchronized void resetCooldownTimes() {
		cooldownWakeupTime = -1;
	}
	
	@Override
	public void requeueAfterCooldown(Key key, long time) {
		if(cooldownWakeupTime > time) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Not requeueing as deadline has not passed yet");
			return;
		}
		if(!(key.equals(this.key.getNodeKey()))) {
			Logger.error(this, "Got requeueAfterCooldown for wrong key: "+key+" but mine is "+this.key.getNodeKey()+" for "+this.key);
			return;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Requeueing after cooldown "+key+" for "+this);
		schedule();
	}
	
}
