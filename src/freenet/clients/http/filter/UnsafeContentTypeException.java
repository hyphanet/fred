package freenet.clients.http.filter;

/**
 * Thrown by the filter when it cannot guarantee the safety of the data, because it is an unknown type,
 * because it cannot be filtered, or because we do not know how to filter it.
 * 
 * Base class for UnknownContentTypeException and KnownUnsafeContentTypeException.
 */
public abstract class UnsafeContentTypeException extends Exception {

	/**
	 * Get the contents of the error page.
	 */
	public abstract String getExplanation();
	
	/**
	 * Get the title of the error page.
	 */
	public abstract String getHTMLEncodedTitle();
	
	/**
	 * Get the raw title of the error page. (May be unsafe for HTML).
	 */
	public abstract String getRawTitle();
	
}
