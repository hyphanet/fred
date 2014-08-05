package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Carries the result of a content filter test back to the client.
 */
public class FilterResultMessage extends DataCarryingMessage {
	public static final String NAME = "FilterResult";

	private final String identifier;
	private final String charset;
	private final String mimeType;
	private final boolean unsafeContentType;
	private final long dataLength;
	
	public FilterResultMessage(String identifier, String charset, String mimeType, boolean unsafeContentType, Bucket bucket) {
		this.identifier = identifier;
		this.charset = charset;
		this.mimeType = mimeType;
		this.unsafeContentType = unsafeContentType;
		if (unsafeContentType) {
			this.dataLength = -1;
		} else {
			this.dataLength = bucket.size();
			this.bucket = bucket;
		}
	}

	@Override
	String getIdentifier() {
		return identifier;
	}

	@Override
	boolean isGlobal() {
		return false;
	}

	@Override
	long dataLength() {
		return dataLength;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle(IDENTIFIER, identifier);
		fs.putOverwrite("Charset", charset);
		fs.putOverwrite("MimeType", mimeType);
		fs.put("UnsafeContentType", unsafeContentType);
		fs.put("DataLength", dataLength);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, NAME + " goes from server to client not the other way around", null, false);
	}

}
