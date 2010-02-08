/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public abstract class SendableGet extends BaseSendableGet {

	/** Parent BaseClientGetter. Required for schedulers. */
	public final ClientRequester parent;
	
	/** Get a numbered key to fetch. */
	public abstract ClientKey getKey(Object token, ObjectContainer container);
	
	@Override
	public Key getNodeKey(SendableRequestItem token, ObjectContainer container) {
		ClientKey key = getKey(token, container);
		if(key == null) return null;
		return key.getNodeKey(true);
	}
	
	/**
	 * What keys are we interested in? For purposes of checking the datastore.
	 * This is in SendableGet, *not* KeyListener, in order to deal with it in
	 * smaller chunks.
	 * @param container Database handle.
	 */
	public abstract Key[] listKeys(ObjectContainer container);

	/** Get the fetch context (settings) object. */
	public abstract FetchContext getContext();
	
	/** Called when/if the low-level request fails. */
	public abstract void onFailure(LowLevelGetException e, Object token, ObjectContainer container, ClientContext context);
	
	/** Should the request ignore the datastore? */
	public abstract boolean ignoreStore();

	// Implementation

	public SendableGet(ClientRequester parent) {
		super(parent.persistent());
		this.parent = parent;
	}
	
	static final SendableGetRequestSender sender = new SendableGetRequestSender();
	
	@Override
	public SendableRequestSender getSender(ObjectContainer container, ClientContext context) {
		return sender;
	}
	
	@Override
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

	/**
	 * An internal error occurred, effecting this SendableGet, independantly of any ChosenBlock's.
	 */
	@Override
	public void internalError(final Throwable t, final RequestScheduler sched, ObjectContainer container, ClientContext context, boolean persistent) {
		Logger.error(this, "Internal error on "+this+" : "+t, t);
		sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, t.getMessage(), t), NativeThread.MAX_PRIORITY, persistent);
	}

	/**
	 * Requeue a key after it has been on the cooldown queue for a while.
	 * Only requeue if our requeue time is less than or equal to the given time.
	 * @param key
	 */
	public abstract void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context);

	@Override
	public final boolean isInsert() {
		return false;
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.delete(this);
	}

	/** Caller must activate to depth 1 before calling */
	public boolean isStorageBroken(ObjectContainer container) {
		if(!container.ext().isActive(this))
			throw new IllegalStateException("Must be activated first!");
		if(!persistent) {
			Logger.error(this, "Not persistent?!");
			return true;
		}
		return false;
	}
	
	public void unregister(ObjectContainer container, ClientContext context, short oldPrio) {
		super.unregister(container, context, oldPrio);
		context.checker.removeRequest(this, persistent, container, context, oldPrio == -1 ? getPriorityClass(container) : oldPrio);
	}
}
