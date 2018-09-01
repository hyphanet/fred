package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SubscribedUSKSendingToNetworkMessage extends FCPMessage {

	final String identifier;
	
	SubscribedUSKSendingToNetworkMessage(String id) {
		identifier = id;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		return fs;
	}

	@Override
	public String getName() {
		return "SubscribedUSKSendingToNetwork";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new UnsupportedOperationException();
	}

}
