/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.client.FetchContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.support.Logger;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public abstract class SendableGet extends SendableRequest {

	/** Is this an SSK? */
	public abstract boolean isSSK();
	
	/** Parent BaseClientGetter. Required for schedulers. */
	public final ClientRequester parent;
	
	/** Choose a key to fetch.
	 * @return An integer identifying a specific key. -1 indicates no keys available. */
	public abstract int chooseKey();
	
	/** All key identifiers */
	public abstract int[] allKeys();
	
	/** Get a numbered key to fetch. */
	public abstract ClientKey getKey(int token);
	
	/** Get the fetch context (settings) object. */
	public abstract FetchContext getContext();
	
	/** Called when/if the low-level request succeeds. */
	public abstract void onSuccess(ClientKeyBlock block, boolean fromStore, int token);
	
	/** Called when/if the low-level request fails. */
	public abstract void onFailure(LowLevelGetException e, int token);
	
	/** Should the request ignore the datastore? */
	public abstract boolean ignoreStore();

	/** If true, don't cache local requests */
	public abstract boolean dontCache();

	// Implementation

	public SendableGet(ClientRequester parent) {
		this.parent = parent;
	}
	
	/** Do the request, blocking. Called by RequestStarter. 
	 * @return True if a request was executed. False if caller should try to find another request, and remove
	 * this one from the queue. */
	public boolean send(NodeClientCore core, RequestScheduler sched) {
		FetchContext ctx = getContext();
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		while(true) {
			synchronized (this) {
				if(isCancelled()) {
					if(logMINOR) Logger.minor(this, "Cancelled: "+this);
					onFailure(new LowLevelGetException(LowLevelGetException.CANCELLED), -1);
					return false;
				}	
			}
			ClientKeyBlock block;
			int keyNum = -1;
			try {
				keyNum = chooseKey();
				if(keyNum == -1) {
					if(logMINOR) Logger.minor(this, "No more keys: "+this);
					return false;
				}
				try {
					ClientKey key = getKey(keyNum);
					if(key == null) {
						if(logMINOR) Logger.minor(this, "No key "+keyNum+": "+this);
						continue;
					}
					block = core.realGetKey(key, ctx.localRequestOnly, ctx.cacheLocalRequests, ctx.ignoreStore);
				} catch (LowLevelGetException e) {
					onFailure(e, keyNum);
					return true;
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
					onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), keyNum);
					return true;
				}
				onSuccess(block, false, keyNum);
				sched.succeeded(this.getParentGrabArray());
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), keyNum);
				return true;
			}
			return true;
		}
	}

	public void schedule() {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Scheduling "+this);
		getScheduler().register(this);
	}
	
	public ClientRequestScheduler getScheduler() {
		if(isSSK())
			return parent.sskScheduler;
		else
			return parent.chkScheduler;
	}

	public abstract void onGotKey(Key key, KeyBlock block);
	
	public final void unregister() {
		getScheduler().removePendingKeys(this, false);
		super.unregister();
	}
	
	public final void unregisterKey(Key key) {
		getScheduler().removePendingKey(this, false, key);
	}
	
}
