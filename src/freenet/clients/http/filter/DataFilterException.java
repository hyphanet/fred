package freenet.clients.http.filter;

/**
 * Exception thrown when the data cannot be filtered.
 */
public class DataFilterException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;

	final String rawTitle;
	final String encodedTitle;
	final String explanation;
	
	DataFilterException(String raw, String encoded, String explanation) {
		this.rawTitle = raw;
		this.encodedTitle = encoded;
		this.explanation = explanation;
	}
	
	public String getExplanation() {
		return explanation;
	}

	public String getHTMLEncodedTitle() {
		return encodedTitle;
	}

	public String getRawTitle() {
		return rawTitle;
	}

}
