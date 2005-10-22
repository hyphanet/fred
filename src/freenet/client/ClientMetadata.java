package freenet.client;

/**
 * Stores the metadata that the client might actually be interested in.
 */
public class ClientMetadata {
	
	/** Default MIME type - what to set it to if we don't know any better */
	public static final String DEFAULT_MIME_TYPE = "application/octet-stream";
	
	/** The document MIME type */
	private String mimeType;

	ClientMetadata(String mime) {
		mimeType = mime;
	}

	/** Create an empty ClientMetadata instance */
	ClientMetadata() {
		mimeType = null;
	}
	
	public String getMIMEType() {
		if(mimeType == null || mimeType.length() == 0)
			return DEFAULT_MIME_TYPE;
		return mimeType;
	}

	/**
	 * Merge the given ClientMetadata, without overwriting our
	 * existing information.
	 */
	public void mergeNoOverwrite(ClientMetadata clientMetadata) {
		if(mimeType == null || mimeType.equals(""))
			mimeType = clientMetadata.mimeType;
	}
}
