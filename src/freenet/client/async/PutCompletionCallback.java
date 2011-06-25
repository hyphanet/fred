package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.support.api.Bucket;

/**
 * Callback called when part of a put request completes.
 */
public interface PutCompletionCallback {

	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context);
	
	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context);

	/** Called when we know the final URI of the state in question. The currentState eventually calls this
	 * on the ClientPutter, which relays to the fcp layer, which sends a URIGenerated message. */
	public void onEncode(BaseClientKey usk, ClientPutState state, ObjectContainer container, ClientContext context);
	
	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container);
	
	/** Only called if explicitly asked for, in which case, generally
	 * the metadata won't be inserted. Won't be called if there isn't
	 * any!
	 */
	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context);
	
	/** Called as an alternative to onEncode, if a metadata length threshold 
	 * was specified. Lower-level insert states, such as SplitFileInserter, will 
	 * call onMetadata() instead. Higher level insert states will call this 
	 * version or onEncode. Callee must free the Bucket. 
	 * FIXME arguably we should split the interface, it might simplify e.g. 
	 * SingleFileInserter.SplitHandler.
	 */
	public void onMetadata(Bucket meta, ClientPutState state, ObjectContainer container, ClientContext context);
	
	/** Called when enough data has been inserted that the file can be
	 * retrieved, even if not all data has been inserted yet. Note that this
	 * is only supported for splitfiles; if you get onSuccess() first, assume
	 * that onFetchable() isn't coming. */
	public void onFetchable(ClientPutState state, ObjectContainer container);
	
	/** Called when the ClientPutState knows that it knows about
	 * all the blocks it will need to put.
	 */
	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context);

}
