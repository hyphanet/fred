package freenet.support;

public interface RemoveRandom {

	/** Remove and return a random RandomGrabArrayItem. Should be fast. */
	public RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding);

	/** Just for consistency checking */
	public boolean persistent();
}
