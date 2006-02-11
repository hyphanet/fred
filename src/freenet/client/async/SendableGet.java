package freenet.client.async;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.node.LowLevelGetException;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public interface SendableGet extends SendableRequest {

	public ClientKey getKey();
	
	/** Called when/if the low-level request succeeds. */
	public void onSuccess(ClientKeyBlock block, boolean fromStore);
	
	/** Called when/if the low-level request fails. */
	public void onFailure(LowLevelGetException e);
	
	/** Should the request ignore the datastore? */
	public boolean ignoreStore();
}
