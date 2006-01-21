package freenet.client.async;

import freenet.client.InserterException;
import freenet.keys.ClientKey;

/**
 * Callback called when part of a put request completes.
 */
public interface PutCompletionCallback {

	public void onSuccess(ClientPutState state);
	
	public void onFailure(InserterException e, ClientPutState state);

	public void onEncode(ClientKey key);
	
}
