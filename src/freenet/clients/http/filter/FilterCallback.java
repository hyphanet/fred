package freenet.clients.http.filter;


/**
 * Callback to be provided to a content filter.
 */
public interface FilterCallback {

	/**
	 * Process a URI.
	 * If it cannot be turned into something sufficiently safe, then return null.
	 * @param overrideType Force the return type.
	 * @throws CommentException If the URI is nvalid or unacceptable in some way.
	 */
	public String processURI(String uri, String overrideType) throws CommentException;

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

	/**
	 * Process a form on the page.
	 * @param method The form sending method. Normally GET or POST.
	 * @param action The URI to send the form to.
	 * @return The new action URI, or null if the form is not allowed.
	 * @throws CommentException 
	 */
	public String processForm(String method, String action) throws CommentException;
	
}
