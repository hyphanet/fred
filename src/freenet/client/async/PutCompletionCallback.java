package freenet.client.async;

import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.support.api.Bucket;
import freenet.support.io.ResumeFailedException;

/**
 * Callback called when part of a put request completes.
 */
public interface PutCompletionCallback {

	public void onSuccess(ClientPutState state, ClientContext context);
	
	public void onFailure(InsertException e, ClientPutState state, ClientContext context);

	/** Called when we know the final URI of the state in question. The currentState eventually calls this
	 * on the ClientPutter, which relays to the fcp layer, which sends a URIGenerated message. */
	public void onEncode(BaseClientKey usk, ClientPutState state, ClientContext context);
	
	public void onTransition(ClientPutState oldState, ClientPutState newState, ClientContext context);
	
	/** Only called if explicitly asked for, in which case, generally
	 * the metadata won't be inserted. Won't be called if there isn't
	 * any!
	 */
	public void onMetadata(Metadata m, ClientPutState state, ClientContext context);
	
	/** Called as an alternative to onEncode, if a metadata length threshold 
	 * was specified. Lower-level insert states, such as SplitFileInserter, will 
	 * call onMetadata() instead. Higher level insert states will call this 
	 * version or onEncode. Callee must free the Bucket. 
	 * FIXME arguably we should split the interface, it might simplify e.g. 
	 * SingleFileInserter.SplitHandler.
	 */
	public void onMetadata(Bucket meta, ClientPutState state, ClientContext context);
	
	/** Called when enough data has been inserted that the file can be
	 * retrieved, even if not all data has been inserted yet. Note that this
	 * is only supported for splitfiles; if you get onSuccess() first, assume
	 * that onFetchable() isn't coming. */
	public void onFetchable(ClientPutState state);
	
	/** Called when the ClientPutState knows that it knows about
	 * all the blocks it will need to put.
	 */
	public void onBlockSetFinished(ClientPutState state, ClientContext context);
	
    /** Called on restarting the node for a persistent request. The request must re-schedule 
     * itself. 
     * @throws InsertException */
    public void onResume(ClientContext context) throws InsertException, ResumeFailedException;

}
