package freenet.support;

public interface RandomGrabArrayItem {

	/** If true, will be automatically removed from the RGA, and not returned. */
	public boolean isFinished();
	
	/** Can this item be removed from the queue? 
	 * Called immediately after finding a request to remove.
	 * If returns false, the item will remain in the queue and may be chosen again. */
	public boolean canRemove();
	
}
