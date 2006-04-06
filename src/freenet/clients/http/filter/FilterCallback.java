package freenet.clients.http.filter;

/**
 * Callback to be provided to a content filter.
 */
public interface FilterCallback {

	/**
	 * Process a URI.
	 * If it cannot be turned into something sufficiently safe, then return null.
	 * @param overrideType Force the return type.
	 */
	public String processURI(String uri, String overrideType);

	/**
	 * Should we allow GET forms?
	 */
	public boolean allowGetForms();
	
	/**
	 * Should we allow POST forms?
	 */
	public boolean allowPostForms();
	
}
