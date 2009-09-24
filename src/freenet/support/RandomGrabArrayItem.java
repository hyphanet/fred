package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

public interface RandomGrabArrayItem {

	/** If true, will be automatically removed from the RGA, and not returned.
	 * True indicates that the item is no longer needed for some reason - in a request,
	 * usually that it has either been explicitly cancelled or that it is not needed
	 * because other queued blocks have been sufficient. If it becomes useful again,
	 * it must be re-registered.
	 * 
	 * LOCKING: Should hold as few locks as possible as this needs to be called while 
	 * holding the RGA lock(s). */
	public boolean isEmpty(ObjectContainer container);
	
	/** Does this RandomGrabArrayItem support remembering where it is registered? */
	public boolean knowsParentGrabArray();
	
	/** Notify the item that it has been registered on a specific RandomGrabArray */
	public void setParentGrabArray(RandomGrabArray parent, ObjectContainer container);
	
	/** If the item remembers its parent RandomGrabArray, return it */
	public RandomGrabArray getParentGrabArray();
	
	/** This must be the same as the value passed into the RGA constructor.
	 * If the user doesn't implement persistence, simply return false here and 
	 * pass false into the constructor. */
	public boolean persistent();
	
	public boolean isStorageBroken(ObjectContainer container);

	public void removeFrom(ObjectContainer container, ClientContext context);
}
