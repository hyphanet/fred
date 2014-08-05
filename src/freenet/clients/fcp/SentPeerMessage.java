package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SentPeerMessage extends FCPMessage {

	public static final String NAME = "SentPeer";

	public final String identifier;
	public final int nodeStatus;

	public SentPeerMessage(String identifier, int nodeStatus) {
		this.identifier = identifier;
		this.nodeStatus = nodeStatus;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.put("NodeStatus", nodeStatus);
		//TODO Textual description of the node status?
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, getName()
				+ " goes from server to client not the other way around", identifier, false);
	}

}
