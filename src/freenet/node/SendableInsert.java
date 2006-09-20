package freenet.node;

/**
 * Callback interface for a low level insert, which is immediately sendable. These
 * should be registered on the ClientRequestScheduler when we want to send them. It will
 * then, when it is time to send, create a thread, send the request, and call the 
 * callback below.
 */
public interface SendableInsert extends SendableRequest {

	/** Called when we successfully insert the data */
	public void onSuccess();
	
	/** Called when we don't! */
	public void onFailure(LowLevelPutException e);

}
