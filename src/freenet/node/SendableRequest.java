package freenet.node;

import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequestSelector;
import freenet.client.async.ClientRequester;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;
import freenet.support.RandomGrabArrayItem;
import freenet.support.Logger.LogLevel;

/**
 * A low-level request which can be sent immediately. These are registered
 * on the ClientRequestScheduler.
 * LOCKING: Because some subclasses may do wierd things like locking on an external object 
 * (see e.g. SplitFileFetcherSubSegment), if we do take the lock we need to do it last i.e.
 * not call any subclass methods inside it.
 * 
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * restarting downloads or losing uploads. Not all subclasses of this class are actually persisted.
 */
public abstract class SendableRequest implements RandomGrabArrayItem, Serializable {
	
    private static final long serialVersionUID = 1L;

    /** Since we put these into Set's etc, hashCode must be persistent.
	 * Guaranteed not to be 0 unless this is a persistent object that is deactivated. */
	private final int hashCode;
	
	protected final boolean realTimeFlag;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * zero arg c'tor for db4o on jamvm and serialization.
	 */
	protected SendableRequest() {
		hashCode = 0;
		persistent = false;
		realTimeFlag = false;
	}

	SendableRequest(boolean persistent, boolean realTimeFlag) {
		this.persistent = persistent;
		this.realTimeFlag = realTimeFlag;
		int oid = super.hashCode();
		if(oid == 0) oid = 1;
		this.hashCode = oid;
	}
	
	@Override
	public final int hashCode() {
		return hashCode;
	}
	
	protected transient RandomGrabArray parentGrabArray;
	/** Member because must be accessible when only marginally activated */
	protected final boolean persistent;
	
	/** Get the priority class of the request. */
	public abstract short getPriorityClass();
	
	/** Choose a key to fetch. Must not modify any persisted structures, but will update cooldowns
	 * etc to avoid it being chosen again soon. There is a separate callback for when a fetch
	 * fails, succeeds, etc. Hence it is safe to call these outside of the PersistentJobRunner.
	 * @return An object identifying a specific key. null indicates no keys available. */
	public abstract SendableRequestItem chooseKey(KeysFetchingLocally keys, ClientContext context);
	
	/** All key identifiers. Including those not currently eligible to be sent because 
	 * they are on a cooldown queue, requests for them are in progress, etc. */
	public abstract long countAllKeys(ClientContext context);

	/** All key identifiers currently eligible to be sent. Does not include those 
	 * currently running, on the cooldown queue etc. */
	public abstract long countSendableKeys(ClientContext context);

	/**
	 * Get or create a SendableRequestSender for this object. This is a non-persistent
	 * object used to send the requests. @see SendableGet.getSender().
	 * @param container A database handle may be necessary for creating it.
	 * @param context A client context may also be necessary.
	 * @return
	 */
	public abstract SendableRequestSender getSender(ClientContext context);
	
	/** If true, the request has been cancelled, or has completed, either way it need not
	 * be registered any more. isEmpty() on the other hand means there are no queued blocks.
	 */
	public abstract boolean isCancelled();
	
	/** Get client context object. This isn't called as frequently as you might expect 
	 * - once on registration, and then when there is an error. So it doesn't need to be
	 * stored on the request itself, hence we pass in a container. */
	public abstract RequestClient getClient();
	
	/** Is this request persistent? MUST NOT CHANGE. */
	public final boolean persistent() {
		return persistent;
	}
	
	/** Get the ClientRequest. This DOES need to be cached on the request itself. */
	public abstract ClientRequester getClientRequest();
	
	@Override
	public synchronized RandomGrabArray getParentGrabArray() {
		return parentGrabArray;
	}
	
	/** Grab it to avoid race condition when unregistering twice in parallel. */
	private synchronized RandomGrabArray grabParentGrabArray() {
		RandomGrabArray ret = parentGrabArray;
		parentGrabArray = null;
		return ret;
	}
	
	@Override
	public boolean knowsParentGrabArray() {
		return true;
	}
	
	@Override
	public synchronized void setParentGrabArray(RandomGrabArray parent) {
		parentGrabArray = parent;
	}
	
	/** Unregister the request.
	 * @param container
	 * @param context
	 * @param oldPrio If we are changing priorities it can matter what the old priority is.
	 * However the parent method, SendableRequest, ignores this. In any case, 
	 * (short)-1 means not specified (look it up).
	 */
	public void unregister(ClientContext context, short oldPrio) {
		RandomGrabArray arr = grabParentGrabArray();
		if(arr != null) {
			synchronized(getScheduler(context)) {
				arr.remove(this, context);
			}
		} else {
			// Should this be a higher priority?
			if(logMINOR)
				Logger.minor(this, "Cannot unregister "+this+" : not registered", new Exception("debug"));
		}
		ClientRequester cr = getClientRequest();
		getScheduler(context).removeFromAllRequestsByClientRequest(cr, this, true);
		// FIXME should we deactivate??
		//if(persistent) container.deactivate(cr, 1);
	}
	
	public abstract ClientRequestScheduler getScheduler(ClientContext context);

	/** Is this an SSK? For purposes of determining which scheduler to use. */
	public abstract boolean isSSK();
	
	/** Is this an insert? For purposes of determining which scheduler to use. */
	public abstract boolean isInsert();
	
	/** Requeue after an internal error */
	public abstract void internalError(Throwable t, RequestScheduler sched, ClientContext context, boolean persistent);

	/** Must be called when we retry a block. 
	 * LOCKING: Caller should hold as few locks as possible */ 
	public void clearCooldown(ClientContext context, boolean definitelyExists) {
		// The request is no longer running, therefore presumably it can be selected, or it's been removed.
		// Stuff that uses the cooldown queue will set or clear depending on whether we retry, but
		// we clear here for stuff that doesn't use it.
		// Note also that the performance cost of going over that particular part of the tree again should be very low.
		RandomGrabArray rga = getParentGrabArray();
		ClientRequestScheduler sched = getScheduler(context);
		ClientRequestSelector selector = sched.getSelector();
		synchronized(selector) {
			selector.clearCachedWakeup(this, context);
			// It is possible that the parent was added to the cache because e.g. a request was running for the same key.
			// We should wake up the parent as well even if this item is not in cooldown.
			if(rga != null)
				selector.clearCachedWakeup(rga, context);
			// If we didn't actually get queued, we should wake up the starter, for the same reason we clearCachedWakeup().
		}
		sched.wakeStarter();
	}
	
	public boolean realTimeFlag() {
		return realTimeFlag;
	}

	protected final String objectToString() {
		return super.toString();
	}
	
    @Override
    public boolean reduceCooldownTime(long wakeupTime) {
        throw new UnsupportedOperationException("Request cannot set cooldown time, only nodes further up the tree can do that");
    }

    @Override
    public void clearCooldownTime(ClientContext context) {
        throw new UnsupportedOperationException("Request cannot set cooldown time, only nodes further up the tree can do that");
    }

}
