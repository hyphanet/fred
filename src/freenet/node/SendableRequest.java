package freenet.node;

import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.client.async.PersistentChosenBlock;
import freenet.client.async.PersistentChosenRequest;
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
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public abstract class SendableRequest implements RandomGrabArrayItem {
	
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
	 * zero arg c'tor for db4o on jamvm
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
	
	protected RandomGrabArray parentGrabArray;
	/** Member because must be accessible when only marginally activated */
	protected final boolean persistent;
	
	/** Get the priority class of the request. */
	public abstract short getPriorityClass(ObjectContainer container);
	
	/** Choose a key to fetch. Removes the block number from any internal queues 
	 * (but not the key itself, implementors must have a separate queue of block 
	 * numbers and mapping of block numbers to keys).
	 * @return An object identifying a specific key. null indicates no keys available. */
	public abstract SendableRequestItem chooseKey(KeysFetchingLocally keys, ObjectContainer container, ClientContext context);
	
	/** All key identifiers. Including those not currently eligible to be sent because 
	 * they are on a cooldown queue, requests for them are in progress, etc. */
	public abstract long countAllKeys(ObjectContainer container, ClientContext context);

	/** All key identifiers currently eligible to be sent. Does not include those 
	 * currently running, on the cooldown queue etc. */
	public abstract long countSendableKeys(ObjectContainer container, ClientContext context);

	/**
	 * Get or create a SendableRequestSender for this object. This is a non-persistent
	 * object used to send the requests. @see SendableGet.getSender().
	 * @param container A database handle may be necessary for creating it.
	 * @param context A client context may also be necessary.
	 * @return
	 */
	public abstract SendableRequestSender getSender(ObjectContainer container, ClientContext context);
	
	/** If true, the request has been cancelled, or has completed, either way it need not
	 * be registered any more. isEmpty() on the other hand means there are no queued blocks.
	 */
	public abstract boolean isCancelled(ObjectContainer container);
	
	/** Get client context object. This isn't called as frequently as you might expect 
	 * - once on registration, and then when there is an error. So it doesn't need to be
	 * stored on the request itself, hence we pass in a container. */
	public abstract RequestClient getClient(ObjectContainer container);
	
	/** Is this request persistent? MUST NOT CHANGE. */
	@Override
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
	private synchronized RandomGrabArray grabParentGrabArray(ObjectContainer container) {
		RandomGrabArray ret = parentGrabArray;
		parentGrabArray = null;
		if(persistent())
			container.store(this);
		return ret;
	}
	
	@Override
	public boolean knowsParentGrabArray() {
		return true;
	}
	
	@Override
	public synchronized void setParentGrabArray(RandomGrabArray parent, ObjectContainer container) {
		parentGrabArray = parent;
		if(persistent())
			container.store(this);
	}
	
	/** Unregister the request.
	 * @param container
	 * @param context
	 * @param oldPrio If we are changing priorities it can matter what the old priority is.
	 * However the parent method, SendableRequest, ignores this. In any case, 
	 * (short)-1 means not specified (look it up).
	 */
	public void unregister(ObjectContainer container, ClientContext context, short oldPrio) {
		RandomGrabArray arr = grabParentGrabArray(container);
		if(arr != null) {
			if(persistent)
				container.activate(arr, 1);
			synchronized(getScheduler(container, context)) {
				arr.remove(this, container, context);
			}
		} else {
			// Should this be a higher priority?
			if(logMINOR)
				Logger.minor(this, "Cannot unregister "+this+" : not registered", new Exception("debug"));
		}
		ClientRequester cr = getClientRequest();
		if(persistent)
			container.activate(cr, 1);
		getScheduler(container, context).removeFromAllRequestsByClientRequest(cr, this, true, container);
		// FIXME should we deactivate??
		//if(persistent) container.deactivate(cr, 1);
	}
	
	public abstract ClientRequestScheduler getScheduler(ObjectContainer container, ClientContext context);

	/** Is this an SSK? For purposes of determining which scheduler to use. */
	public abstract boolean isSSK();
	
	/** Is this an insert? For purposes of determining which scheduler to use. */
	public abstract boolean isInsert();
	
	/** Requeue after an internal error */
	public abstract void internalError(Throwable t, RequestScheduler sched, ObjectContainer container, ClientContext context, boolean persistent);

	/** Construct a full set of ChosenBlock's for a persistent request. These are transient, so we will need to clone keys
	 * etc. */
	public abstract List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, KeysFetchingLocally keys, ObjectContainer container, ClientContext context);

	@Override
	public boolean isStorageBroken(ObjectContainer container) {
		return false;
	}

	/** Must be called when we retry a block. 
	 * LOCKING: Caller should hold as few locks as possible */ 
	public void clearCooldown(ObjectContainer container, ClientContext context, boolean definitelyExists) {
		if(persistent && !container.ext().isStored(this)) {
			if(definitelyExists)
				Logger.error(this, "Clear cooldown on persistent request "+this+" but already removed");
			else if(hashCode != 0) // conceivably there might be a problem, but unlikely 
				Logger.normal(this, "Clear cooldown on persistent request "+this+" but already removed");
			else // removed, not likely to be an issue.
				Logger.minor(this, "Clear cooldown on persistent request "+this+" but already removed");
			return;
		}
		// The request is no longer running, therefore presumably it can be selected, or it's been removed.
		// Stuff that uses the cooldown queue will set or clear depending on whether we retry, but
		// we clear here for stuff that doesn't use it.
		// Note also that the performance cost of going over that particular part of the tree again should be very low.
		RandomGrabArray rga = getParentGrabArray();
		ClientRequestScheduler sched = getScheduler(container, context);
		synchronized(sched) {
			context.cooldownTracker.clearCachedWakeup(this, persistent, container);
			// It is possible that the parent was added to the cache because e.g. a request was running for the same key.
			// We should wake up the parent as well even if this item is not in cooldown.
			if(rga != null)
				context.cooldownTracker.clearCachedWakeup(rga, persistent, container);
			// If we didn't actually get queued, we should wake up the starter, for the same reason we clearCachedWakeup().
		}
		sched.wakeStarter();
	}
	
	public boolean realTimeFlag() {
		return realTimeFlag;
	}

	/** This always uses the default implementation for persistent objects, which will return a more or 
	 * less unique value for activated persistent objects, and class@0 for deactivated ones. Transient
	 * only objects may override via overrideToString(). REDFLAG DB4O: This is not necessary if we get
	 * rid of db4o or if we move away from manual activation.
	 */
	public final String toString() {
		if(hashCode != 0 && !persistent)
			return transientToString();
		return super.toString();
	}
	
	protected final String objectToString() {
		return super.toString();
	}

	/** Method to safely override toString(), will only be called if the object is transient. */
	protected String transientToString() {
		return super.toString();
	}
}
