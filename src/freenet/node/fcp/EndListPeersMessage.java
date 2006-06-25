package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class EndListPeersMessage extends FCPMessage {

	static final String name = "EndListPeers";
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "EndListPeers goes from server to client not the other way around", null);
	}

}
