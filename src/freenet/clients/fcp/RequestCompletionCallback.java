package freenet.clients.fcp;

public interface RequestCompletionCallback {

	/**
	 * Callback called when a request succeeds.
	 */
	public void notifySuccess(ClientRequest req);
	
	/**
	 * Callback called when a request fails
	 */
	public void notifyFailure(ClientRequest req);
	
	/**
	 * Callback when a request is removed
	 */
	public void onRemove(ClientRequest req);
	
}
