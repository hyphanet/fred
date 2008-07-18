/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.async.ChosenRequest;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public abstract class SendableGet extends BaseSendableGet {

	/** Is this an SSK? */
	public abstract boolean isSSK();
	
	/** Parent BaseClientGetter. Required for schedulers. */
	public final ClientRequester parent;
	
	/** Get a numbered key to fetch. */
	public abstract ClientKey getKey(Object token, ObjectContainer container);
	
	public Key getNodeKey(Object token, ObjectContainer container) {
		ClientKey key = getKey(token, container);
		if(key == null) return null;
		return key.getNodeKey();
	}
	
	/** Get the fetch context (settings) object. */
	public abstract FetchContext getContext();
	
	/** Called when/if the low-level request succeeds. */
	public abstract void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, ObjectContainer container, ClientContext context);
	
	/** Called when/if the low-level request fails. */
	public abstract void onFailure(LowLevelGetException e, Object token, ObjectContainer container, ClientContext context);
	
	/** Should the request ignore the datastore? */
	public abstract boolean ignoreStore();

	/** If true, don't cache local requests */
	public abstract boolean dontCache();

	// Implementation

	public SendableGet(ClientRequester parent) {
		super(parent.persistent());
		this.parent = parent;
	}
	
	/** Do the request, blocking. Called by RequestStarter. 
	 * Also responsible for deleting it.
	 * @return True if a request was executed. False if caller should try to find another request, and remove
	 * this one from the queue. */
	public boolean send(NodeClientCore core, final RequestScheduler sched, ChosenRequest req) {
		Object keyNum = req.token;
		ClientKey key = req.ckey;
		if(key == null) {
			Logger.error(this, "Key is null in send(): keyNum = "+keyNum+" for "+this);
			return false;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Sending get for key "+keyNum+" : "+key);
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if((!req.isPersistent()) && isCancelled(null)) {
			if(logMINOR) Logger.minor(this, "Cancelled: "+this);
			sched.callFailure(this, new LowLevelGetException(LowLevelGetException.CANCELLED), keyNum, NativeThread.NORM_PRIORITY+1, req, req.isPersistent());
			return false;
		}
		try {
			try {
				core.realGetKey(key, req.localRequestOnly, req.cacheLocalRequests, req.ignoreStore);
			} catch (final LowLevelGetException e) {
				sched.callFailure(this, e, keyNum, NativeThread.NORM_PRIORITY+1, req, req.isPersistent());
				return true;
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), keyNum, NativeThread.NORM_PRIORITY+1, req, req.isPersistent());
				return true;
			}
			// We must remove the request even in this case.
			// On other paths, callFailure() will do the removal.
			sched.removeChosenRequest(req);
			// Don't call onSuccess(), it will be called for us by backdoor coalescing.
			sched.succeeded(this, req);
			
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), keyNum, NativeThread.NORM_PRIORITY+1, req, req.isPersistent());
			return true;
		}
		return true;
	}

	public ClientRequestScheduler getScheduler(ClientContext context) {
		if(isSSK())
			return context.getSskFetchScheduler();
		else
			return context.getChkFetchScheduler();
	}

	/**
	 * Get the time at which the key specified by the given token will wake up from the 
	 * cooldown queue.
	 * @param token
	 * @return
	 */
	public abstract long getCooldownWakeup(Object token, ObjectContainer container);
	
	public abstract long getCooldownWakeupByKey(Key key, ObjectContainer container);
	
	/** Reset the cooldown times when the request is reregistered. */
	public abstract void resetCooldownTimes(ObjectContainer container);

	public void internalError(final Object keyNum, final Throwable t, final RequestScheduler sched, ObjectContainer container, ClientContext context, boolean persistent) {
		sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, t.getMessage(), t), keyNum, NativeThread.MAX_PRIORITY, null, persistent);
	}

	/**
	 * Requeue a key after it has been on the cooldown queue for a while.
	 * Only requeue if our requeue time is less than or equal to the given time.
	 * @param key
	 */
	public abstract void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context);

}
