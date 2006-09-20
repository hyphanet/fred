package freenet.node;

import freenet.client.async.ClientRequester;
import freenet.support.RandomGrabArrayItem;

/**
 * A low-level request which can be sent immediately. These are registered
 * on the ClientRequestScheduler.
 */
public interface SendableRequest extends RandomGrabArrayItem {
	
	public short getPriorityClass();
	
	public int getRetryCount();
	
	/** ONLY called by RequestStarter */
	public void send(NodeClientCore node);
	
	/** Get client context object */
	public Object getClient();
	
	/** Get the ClientRequest */
	public ClientRequester getClientRequest();

}
