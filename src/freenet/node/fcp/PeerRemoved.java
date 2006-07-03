package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PeerRemoved extends FCPMessage {

	static final String name = "PeerRemoved";
	final String identity;
	final String nodeIdentifier;
	
	public PeerRemoved(String identity, String nodeIdentifier) {
		this.identity = identity;
		this.nodeIdentifier = nodeIdentifier;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.put("Identity", identity);
		fs.put("NodeIdentifier", nodeIdentifier);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PeerRemoved goes from server to client not the other way around", null);
	}

}
