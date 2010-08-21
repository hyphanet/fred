package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.keys.Key;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public abstract class BaseSendableGet extends SendableRequest {
	
	protected BaseSendableGet(boolean persistent) {
		super(persistent);
	}

	/** Get a numbered key to fetch. */
	public abstract Key getNodeKey(SendableRequestItem token, ObjectContainer container);
	
	/** Called after checking the datastore and before registering the request to be 
	 * sent. Some gets may want to cancel here, some may want to send an event to FCP. 
	 * @param toNetwork If true, we are actually going to send requests (unless we
	 * cancel in this callback). If false, we completed all the work assigned. */
	public abstract void preRegister(ObjectContainer container, ClientContext context, boolean toNetwork);
}
