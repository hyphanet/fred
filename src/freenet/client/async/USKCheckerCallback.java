package freenet.client.async;

import freenet.keys.ClientSSKBlock;

/**
 * Callback for a USKChecker
 */
interface USKCheckerCallback {

	/** Data Not Found */
	public void onDNF();
	
	/** Successfully found the latest version of the key 
	 * @param block */
	public void onSuccess(ClientSSKBlock block);
	
	/** Error committed by author */
	public void onFatalAuthorError();
	
	/** Network on our node or on nodes we have been talking to */
	public void onNetworkError();

	/** Request cancelled */
	public void onCancelled();
	
	/** Get priority to run the request at */
	public short getPriority();
	
}
