package freenet.node.fcp;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class URIGeneratedMessage extends FCPMessage {

	private final FreenetURI uri;
	private final String identifier;
	
	public URIGeneratedMessage(FreenetURI uri, String identifier) {
		// FIXME remove when debugged the constant-stream-of-URIGenerated's bug.
		Logger.minor(this, "URIGenerated created for "+uri+" on "+identifier+" ("+this+")", new Exception("debug"));
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
