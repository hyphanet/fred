package freenet.clients.http.filter;

import freenet.keys.FreenetURI;

/**
 * Callback to be provided to a content filter.
 */
public interface FilterCallback {

	/**
	 * Process a URI.
	 * If it cannot be turned into something sufficiently safe, then return null.
	 */
	public FreenetURI processURI(FreenetURI uri);

	/**
	 * Should we allow GET forms?
	 */
	public boolean allowGetForms();
	
	/**
	 * Should we allow POST forms?
	 */
	public boolean allowPostForms();
	
}
