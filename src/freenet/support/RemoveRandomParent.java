package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.RequestSelectionTreeNode;

public interface RemoveRandomParent extends RequestSelectionTreeNode {

	/** Remove the specified RemoveRandom, and propagate upwards if the parent is now empty.
	 * @param context 
	 */
	public void maybeRemove(RemoveRandom r, ClientContext context);

}
