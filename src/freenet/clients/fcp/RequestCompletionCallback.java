package freenet.clients.fcp;

import com.db4o.ObjectContainer;

public interface RequestCompletionCallback {

	/**
	 * Callback called when a request succeeds.
	 */
	public void notifySuccess(ClientRequest req, ObjectContainer container);
	
	/**
	 * Callback called when a request fails
	 */
	public void notifyFailure(ClientRequest req, ObjectContainer container);
	
	/**
	 * Callback when a request is removed
	 */
	public void onRemove(ClientRequest req, ObjectContainer container);
	
}
