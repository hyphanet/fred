package freenet.support.api;

/**
 * An HTTP response.
 */
public final class HTTPReply {

	private final String mimeType;
	private final Bucket data;
	
	public HTTPReply(String mimeType, Bucket data) {
		this.mimeType = mimeType;
		this.data = data;
	}
	
	public final String getMIMEType() {
		return mimeType;
	}
	
	public final Bucket getData() {
		return data;
	}

}
