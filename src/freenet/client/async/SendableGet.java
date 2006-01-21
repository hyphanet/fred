package freenet.client.async;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.node.LowLevelPutException;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public interface SendableGet extends SendableRequest {

	public ClientKey getKey();
	
	/** Called when/if the low-level request succeeds. */
	public void onSuccess(ClientKeyBlock block);
	
	/** Called when/if the low-level request fails. */
	public void onFailure(LowLevelPutException e);
	
}
