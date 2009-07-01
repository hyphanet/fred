package freenet.node.fcp;

import java.io.UnsupportedEncodingException;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.Base64;
import freenet.support.SimpleFieldSet;

public abstract class ReceivedFeedMessage extends FCPMessage {

	public static final String NAME="ReceivedStatusFeed";
	private final String identifier;
	private final String header;
	private final String shortText;
	private final String text;

	public ReceivedFeedMessage(String identifier, String header, String shortText, String text) {
		this.identifier = identifier;
		this.header = header;
		this.shortText = shortText;
		this.text = text;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		try {
			if(header != null)
				fs.putSingle("Header", Base64.encode(header.getBytes("UTF-8")));
			if(shortText != null)
				fs.putSingle("ShortText", Base64.encode(shortText.getBytes("UTF-8")));
			if(text != null)
				fs.putSingle("Text", Base64.encode(text.getBytes("UTF-8")));
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, getName()
				+ " goes from server to client not the other way around", identifier, false);
	}

}
