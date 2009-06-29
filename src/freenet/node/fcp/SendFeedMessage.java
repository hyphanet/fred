package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.DarknetPeerNode;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.SimpleFieldSet;

public abstract class SendFeedMessage extends FCPMessage {

	protected final String identifier;
	protected final String nodeIdentifier;

	public SendFeedMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		nodeIdentifier = fs.get("NodeIdentifier");
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.putSingle("NodeIdentifier", nodeIdentifier);
		return fs;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		PeerNode pn = node.getPeerNode(nodeIdentifier);
		if (pn == null) {
			FCPMessage msg = new UnknownNodeIdentifierMessage(nodeIdentifier, identifier);
			handler.outputHandler.queue(msg);
		} else if (!(pn instanceof DarknetPeerNode)) {
			throw new MessageInvalidException(ProtocolErrorMessage.DARKNET_ONLY,
					getName() + " only available for darknet peers", identifier, false);
		} else {
			int nodeStatus = handleFeed(((DarknetPeerNode) pn));
			handler.outputHandler.queue(new SentFeedMessage(identifier, nodeStatus));
		}
	}

	protected abstract int handleFeed(DarknetPeerNode pn);

}
