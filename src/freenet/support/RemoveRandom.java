package freenet.support;

import com.db4o.ObjectContainer;

public interface RemoveRandom {

	/** Remove and return a random RandomGrabArrayItem. Should be fast. */
	public RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container);

	/** Just for consistency checking */
	public boolean persistent();
}
