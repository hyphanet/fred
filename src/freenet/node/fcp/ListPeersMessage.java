package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ListPeersMessage extends FCPMessage {

	final boolean withMetadata;
	final boolean withVolatile;
	static final String name = "ListPeers";
	
	public ListPeersMessage(SimpleFieldSet fs) {
		withMetadata = Fields.stringToBool(fs.get("WithMetadata"), false);
		withVolatile = Fields.stringToBool(fs.get("WithVolatile"), false);
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(false);
	}
	
	public String getName() {
		return name;
	}
	
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		PeerNode[] nodes = node.getPeerNodes();
		for(int i = 0; i < nodes.length; i++) {
			PeerNode pn = nodes[i];
			handler.outputHandler.queue(new Peer(pn, withMetadata, withVolatile));
		}
		handler.outputHandler.queue(new EndListPeersMessage());
	}
	
}
