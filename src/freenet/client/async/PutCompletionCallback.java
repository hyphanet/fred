package freenet.client.async;

import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.ClientKey;
import freenet.keys.USK;

/**
 * Callback called when part of a put request completes.
 */
public interface PutCompletionCallback {

	public void onSuccess(ClientPutState state);
	
	public void onFailure(InserterException e, ClientPutState state);

	public void onEncode(BaseClientKey usk, ClientPutState state);
	
	public void onTransition(ClientPutState oldState, ClientPutState newState);
	
	/** Only called if explicitly asked for, in which case, generally
	 * the metadata won't be inserted. Won't be called if there isn't
	 * any!
	 */
	public void onMetadata(Metadata m, ClientPutState state);
	
	/** Called when the ClientPutState knows that it knows about
	 * all the blocks it will need to put.
	 */
	public void onBlockSetFinished(ClientPutState state);

}
