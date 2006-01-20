package freenet.client.async;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.node.LowLevelPutException;

/**
 * A low-level request which can be sent immediately. These are registered
 * on the ClientRequestScheduler.
 */
public interface SendableRequest {
	
	public ClientKey getKey();
	
	public short getPriorityClass();
	
	public int getRetryCount();
	
	/** Called when/if the low-level request succeeds. */
	public void onSuccess(ClientKeyBlock block);
	
	/** Called when/if the low-level request fails. */
	public void onFailure(LowLevelPutException e);
	
}
