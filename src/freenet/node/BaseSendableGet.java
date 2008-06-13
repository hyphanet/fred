package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.keys.Key;

public abstract class BaseSendableGet extends SendableRequest {
	
	/** Get a numbered key to fetch. */
	public abstract Key getNodeKey(Object token, ObjectContainer container);
	
	public abstract boolean hasValidKeys(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context);

}
