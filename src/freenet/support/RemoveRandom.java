package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

public interface RemoveRandom {

	/** Remove and return a random RandomGrabArrayItem. Should be fast. */
	public RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container, ClientContext context);

	/** Just for consistency checking */
	public boolean persistent();
	
	public void removeFrom(ObjectContainer container);
}
