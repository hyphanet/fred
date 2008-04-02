package freenet.node;

import freenet.client.async.ClientRequester;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;
import freenet.support.RandomGrabArrayItem;

/**
 * A low-level request which can be sent immediately. These are registered
 * on the ClientRequestScheduler.
 * LOCKING: Because some subclasses may do wierd things like locking on an external object 
 * (see e.g. SplitFileFetcherSubSegment), if we do take the lock we need to do it last i.e.
 * not call any subclass methods inside it.
 */
public abstract class SendableRequest implements RandomGrabArrayItem {
	
	protected RandomGrabArray parentGrabArray;
	
	/** Get the priority class of the request. */
	public abstract short getPriorityClass();
	
	public abstract int getRetryCount();
	
	/** Choose a key to fetch. Removes the block number from any internal queues 
	 * (but not the key itself, implementors must have a separate queue of block 
	 * numbers and mapping of block numbers to keys).
	 * @return An object identifying a specific key. -1 indicates no keys available. */
	public abstract Object chooseKey(KeysFetchingLocally keys);
	
	/** All key identifiers. Including those not currently eligible to be sent because 
	 * they are on a cooldown queue, requests for them are in progress, etc. */
	public abstract Object[] allKeys();

	/** All key identifiers currently eligible to be sent. Does not include those 
	 * currently running, on the cooldown queue etc. */
	public abstract Object[] sendableKeys();

	/** ONLY called by RequestStarter. Start the actual request using the NodeClientCore
	 * provided, and the key and key number earlier got from chooseKey(). 
	 * The request itself may have been removed from the overall queue already.
	 * @param sched The scheduler this request has just been grabbed from.
	 * @param keyNum The key number that was fed into getKeyObject().
	 * @param key The key returned from grabKey().
	 * @return True if a request was sent, false otherwise (in which case the request will
	 * be removed if it hasn't already been). */
	public abstract boolean send(NodeClientCore node, RequestScheduler sched, Object keyNum);
	
	/** If true, the request has been cancelled, or has completed, either way it need not
	 * be registered any more. isEmpty() on the other hand means there are no queued blocks.
	 */
	public abstract boolean isCancelled();
	
	/** Get client context object */
	public abstract Object getClient();
	
	/** Get the ClientRequest */
	public abstract ClientRequester getClientRequest();
	
	public synchronized RandomGrabArray getParentGrabArray() {
		return parentGrabArray;
	}
	
	public boolean knowsParentGrabArray() {
		return true;
	}
	
	public synchronized void setParentGrabArray(RandomGrabArray parent) {
		parentGrabArray = parent;
	}
	
	public void unregister() {
		RandomGrabArray arr = getParentGrabArray();
		if(arr != null) {
			arr.remove(this);
		} else {
			// Should this be a higher priority?
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Cannot unregister "+this+" : not registered", new Exception("debug"));
		}
	}

	/** Requeue after an internal error */
	public abstract void internalError(Object keyNum, Throwable t, RequestScheduler sched);

}
