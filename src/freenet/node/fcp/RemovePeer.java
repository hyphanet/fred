package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.SimpleFieldSet;

public class RemovePeer extends FCPMessage {

	static final String name = "RemovePeer";
	
	final SimpleFieldSet fs;
	
	public RemovePeer(SimpleFieldSet fs) {
		this.fs = fs;
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet();
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		String nodeIdentifier = fs.get("NodeIdentifier");
		PeerNode pn = node.getPeerNode(nodeIdentifier);
		if(pn == null) {
			ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_NODE_IDENTIFIER, false, null, nodeIdentifier);
			handler.outputHandler.queue(msg);
			return;
		}
		String identity = pn.getIdentityString();
		node.removeDarknetConnection(pn);
		handler.outputHandler.queue(new PeerRemoved(identity, nodeIdentifier));
	}

}
