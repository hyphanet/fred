package freenet.node.fcp;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class URIGeneratedMessage extends FCPMessage {

	private final FreenetURI uri;
	private final String identifier;
	
	public URIGeneratedMessage(FreenetURI uri, String identifier) {
		this.uri = uri;
		this.identifier = identifier;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("URI", uri.toString());
		fs.put("Identifier", identifier);
		return fs;
	}

	public String getName() {
		return "URIGenerated";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "URIGenerated goes from server to client not the other way around", identifier);
	}

}
