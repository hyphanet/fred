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

	/**
	 * Process a base URI in the page. Not only is this filtered, it affects all
	 * relative uri's on the page.
	 */
	public String onBaseHref(String baseHref);
	
	/**
	 * Process plain-text. Notification only; can't modify.
	 * Type can be null, or can correspond, for example to HTML tag name around text
	 *    (for example: "title")
	 */
	public void onText(String s, String type);
	
}
