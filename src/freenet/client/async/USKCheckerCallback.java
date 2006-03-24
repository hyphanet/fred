package freenet.client.async;

/**
 * Callback for a USKChecker
 */
interface USKCheckerCallback {

	/** Data Not Found */
	public void onDNF();
	
	/** Successfully found the latest version of the key */
	public void onSuccess();
	
	/** Error committed by author */
	public void onFatalAuthorError();
	
	/** Network on our node or on nodes we have been talking to */
	public void onNetworkError();

	/** Request cancelled */
	public void onCancelled();
	
}
