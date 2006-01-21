package freenet.client.async;

import freenet.keys.ClientKeyBlock;
import freenet.node.LowLevelPutException;

/**
 * Callback interface for a low level insert, which is immediately sendable. These
 * should be registered on the ClientRequestScheduler when we want to send them. It will
 * then, when it is time to send, create a thread, send the request, and call the 
 * callback below.
 */
public interface SendableInsert extends SendableRequest {

	/** Get the ClientKeyBlock to insert. This may be created
	 * just-in-time, and may return null; ClientRequestScheduler
	 * will simply unregister the SendableInsert if this happens.
	 */
	public ClientKeyBlock getBlock();
	
	/** Called when we successfully insert the data */
	public void onSuccess();
	
	/** Called when we don't! */
	public void onFailure(LowLevelPutException e);

}
