package freenet.node;

import freenet.client.async.ChosenBlock;
import freenet.client.async.ClientContext;

/**
 * Interface for class responsible for doing the actual sending of requests.
 * Strictly non-persistent.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public interface SendableRequestSender {
	
	/** ONLY called by RequestStarter. Start the actual request using the NodeClientCore
	 * provided, and the key and key number earlier got from chooseKey(). 
	 * The request itself may have been removed from the overall queue already. For 
	 * persistent requests, the callbacks will be called on the database thread, and we 
	 * will delete the PersistentChosenRequest from there before committing.
	 * @param sched The scheduler this request has just been grabbed from.
	 * @param request The ChosenBlock containing the key, the SendableRequestItem,
	 * and the methods to call to indicate success/failure.
	 * @return True if a request was sent, false otherwise (in which case the request will
	 * be removed if it hasn't already been). */
	public abstract boolean send(NodeClientCore node, RequestScheduler sched, ClientContext context, ChosenBlock request);

	public abstract boolean sendIsBlocking();
	
}
