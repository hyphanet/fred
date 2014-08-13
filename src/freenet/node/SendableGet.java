/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.client.async.SimpleSingleFileFetcher;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public abstract class SendableGet extends BaseSendableGet {

    private static final long serialVersionUID = 1L;
    /** Parent BaseClientGetter. Required for schedulers. */
	public final ClientRequester parent;
	
	/** Get a numbered key to fetch. */
	public abstract ClientKey getKey(SendableRequestItem token);
	
	@Override
	public Key getNodeKey(SendableRequestItem token) {
		ClientKey key = getKey(token);
		if(key == null) return null;
		return key.getNodeKey(true);
	}
	
	/**
	 * What keys are we interested in? For purposes of checking the datastore.
	 * This is in SendableGet, *not* KeyListener, in order to deal with it in
	 * smaller chunks.
	 * @param container Database handle.
	 */
	public abstract Key[] listKeys();

	/** Get the fetch context (settings) object. */
	public abstract FetchContext getContext();
	
	/** Called when/if the low-level request fails. */
	public abstract void onFailure(LowLevelGetException e, SendableRequestItem token, ClientContext context);
	
	// Implementation

	public SendableGet(ClientRequester parent, boolean realTimeFlag) {
		super(parent.persistent(), realTimeFlag);
		this.parent = parent;
	}
	
	protected SendableGet() {
	    // For serialization.
	    parent = null;
	}
	
	static final SendableGetRequestSender sender = new SendableGetRequestSender();
	
	@Override
	public SendableRequestSender getSender(ClientContext context) {
		return sender;
	}
	
	@Override
	public ClientRequestScheduler getScheduler(ClientContext context) {
		if(isSSK())
			return context.getSskFetchScheduler(realTimeFlag);
		else
			return context.getChkFetchScheduler(realTimeFlag);
	}

	/**
	 * Get the time at which the key specified by the given token will wake up from the 
	 * cooldown queue.
	 * @param token
	 * @return
	 */
	public abstract long getCooldownWakeup(SendableRequestItem token, ClientContext context);
	
	/**
	 * An internal error occurred, effecting this SendableGet, independantly of any ChosenBlock's.
	 */
	@Override
	public void internalError(final Throwable t, final RequestScheduler sched, ClientContext context, boolean persistent) {
		Logger.error(this, "Internal error on "+this+" : "+t, t);
		sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, t.getMessage(), t), NativeThread.MAX_PRIORITY, persistent);
	}

	@Override
	public final boolean isInsert() {
		return false;
	}

	@Override
	public void unregister(ClientContext context, short oldPrio) {
		super.unregister(context, oldPrio);
		synchronized(getScheduler(context)) {
			context.cooldownTracker.removeCachedWakeup(this);
		}
		context.checker.removeRequest(this, persistent, context, oldPrio == -1 ? getPriorityClass() : oldPrio);
	}
	
	public static FetchException translateException(LowLevelGetException e) {
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
	        Logger.error(SimpleSingleFileFetcher.class, "Unknown LowLevelGetException code: "+e.code);
	        return new FetchException(FetchException.INTERNAL_ERROR, "Unknown error code: "+e.code);
	    }
	}

}
