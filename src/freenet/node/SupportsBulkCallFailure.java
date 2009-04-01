package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

/**
 * Normally only implemented by SendableGet's. YOU MUST ALSO IMPLEMENT equals() and a hashCode() consistent with it!
 * @author toad
 *
 */
public interface SupportsBulkCallFailure {
	
	/** Process a whole batch of failures at once. */
	public abstract void onFailure(BulkCallFailureItem[] items, ObjectContainer container, ClientContext context);
}
