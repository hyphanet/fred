package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.RequestSelectionTreeNode;

public interface RemoveRandomParent extends RequestSelectionTreeNode {

	/** If the specified RemoveRandom is empty, remove it.
	 * LOCKING: Must be called with no locks held, particularly no locks on the
	 * RemoveRandom, because we take locks in order!
	 * @param context 
	 */
	public void maybeRemove(RemoveRandom r, ClientContext context);

}
