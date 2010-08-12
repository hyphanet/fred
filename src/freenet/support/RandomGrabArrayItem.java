package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.HasCooldownCacheItem;

public interface RandomGrabArrayItem extends HasCooldownCacheItem {

	/** @return -1 if the item is no longer needed and should be removed, because it 
	 * is cancelled, is completing with the blocks it has already, etc. 0 if there are
	 * requests to send now. Otherwise the time at which there will be more requests to
	 * send. If all requests are in flight, returns a time in the distant future e.g.
	 * Long.MAX_VALUE. In both the latter cases, will add itself to the cooldown cache 
	 * via the RandomGrabArrayExclusionList. LOCKING: Should hold as few locks as 
	 * reasonably possible, called inside RGA lock.
	 * @param excluding Can be null.
	 * @param container Database handle.
	 */
	public long getCooldownTime(ObjectContainer container, ClientContext context, long now);
	
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
