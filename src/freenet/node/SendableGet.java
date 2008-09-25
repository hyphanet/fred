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
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.support.io.NativeThread;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public abstract class SendableGet extends BaseSendableGet {

	/** Parent BaseClientGetter. Required for schedulers. */
	public final ClientRequester parent;
	
	/** Get a numbered key to fetch. */
	public abstract ClientKey getKey(Object token, ObjectContainer container);
	
	@Override
	public Key getNodeKey(Object token, ObjectContainer container) {
		ClientKey key = getKey(token, container);
		if(key == null) return null;
		return key.getNodeKey();
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
	
	static final SendableGetRequestSender sender = new SendableGetRequestSender();
	
	public SendableRequestSender getSender(ObjectContainer container, ClientContext context) {
		return sender;
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

	/**
	 * An internal error occurred, effecting this SendableGet, independantly of any ChosenBlock's.
	 */
	@Override
	public void internalError(final Throwable t, final RequestScheduler sched, ObjectContainer container, ClientContext context, boolean persistent) {
		sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, t.getMessage(), t), NativeThread.MAX_PRIORITY, persistent);
	}

	/**
	 * Requeue a key after it has been on the cooldown queue for a while.
	 * Only requeue if our requeue time is less than or equal to the given time.
	 * @param key
	 */
	public abstract void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context);

	public final boolean isInsert() {
		return false;
	}
	
}
