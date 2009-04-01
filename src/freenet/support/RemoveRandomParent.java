package freenet.support;

import com.db4o.ObjectContainer;

public interface RemoveRandomParent {

	/** If the specified RemoveRandom is empty, remove it.
	 * LOCKING: Must be called with no locks held, particularly no locks on the
	 * RemoveRandom, because we take locks in order!
	 */
	public void maybeRemove(RemoveRandom r, ObjectContainer container);

}
