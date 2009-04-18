package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.keys.Key;

public abstract class BaseSendableGet extends SendableRequest {
	
	protected BaseSendableGet(boolean persistent) {
		super(persistent);
	}

	/** Get a numbered key to fetch. */
	public abstract Key getNodeKey(SendableRequestItem token, ObjectContainer container);
	
	public abstract boolean hasValidKeys(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context);

	/** Called after checking the datastore and before registering the request to be 
	 * sent. Some gets may want to cancel here, some may want to send an event to FCP. */
	public abstract void preRegister(ObjectContainer container, ClientContext context);
}
