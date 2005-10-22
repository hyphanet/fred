package freenet.client;

/**
 * Stores the metadata that the client might actually be interested in.
 */
public class ClientMetadata {
	
	/** Default MIME type - what to set it to if we don't know any better */
	public static final String DEFAULT_MIME_TYPE = "application/octet-stream";
	
	/** The document MIME type */
	private String mimeType;

	public String getMIMEType() {
		return mimeType;
	}
}
