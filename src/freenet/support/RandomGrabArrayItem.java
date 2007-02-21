package freenet.support;

public interface RandomGrabArrayItem {

	/** If true, will be automatically removed from the RGA, and not returned.
	 * True indicates that the item is no longer needed for some reason - in a request,
	 * usually that it has either been explicitly cancelled or that it is not needed
	 * because other queued blocks have been sufficient. */
	public boolean isCancelled();
	
	/** Can this item be removed from the queue after it has been handled?
	 * Called immediately after finding a request to remove.
	 * If returns false, the item will remain in the queue and may be chosen again.
	 * Note that in the case of SendableGet's, this is called before choseKey(), so
	 * it needs to return true if there are less than two requests on this object. */
	public boolean canRemove();
	
}
