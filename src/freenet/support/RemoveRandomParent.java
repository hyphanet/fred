package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.HasCooldownCacheItem;

public interface RemoveRandomParent extends HasCooldownCacheItem {

	/** If the specified RemoveRandom is empty, remove it.
	 * LOCKING: Must be called with no locks held, particularly no locks on the
	 * RemoveRandom, because we take locks in order!
	 * @param context 
	 */
	public void maybeRemove(RemoveRandom r, ObjectContainer container, ClientContext context);

}
