package freenet.node;

import freenet.client.async.ClientContext;
import freenet.keys.Key;

/**
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * restarting downloads or losing uploads. (Some children of this class are actually stored)
 * @author toad
 */
public abstract class BaseSendableGet extends SendableRequest {
	
    private static final long serialVersionUID = 1L;

    protected BaseSendableGet(boolean persistent, boolean realTimeFlag) {
		super(persistent, realTimeFlag);
	}
	
	/** Get a numbered key to fetch. */
	public abstract Key getNodeKey(SendableRequestItem token);
	
	/** Called after checking the datastore and before registering the request to be 
	 * sent. Some gets may want to cancel here, some may want to send an event to FCP. 
	 * @param toNetwork If true, we are actually going to send requests (unless we
	 * cancel in this callback). If false, we completed all the work assigned. 
	 * @return True to cancel the request at this stage i.e. not go to network,
	 * in which case *the BSG must handle the failure itself*. */
	public abstract boolean preRegister(ClientContext context, boolean toNetwork);
}
