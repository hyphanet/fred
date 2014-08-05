package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SubscribedUSKRoundFinishedMessage extends FCPMessage {

	final String identifier;
	
	SubscribedUSKRoundFinishedMessage(String id) {
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
		return "SubscribedUSKRoundFinished";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new UnsupportedOperationException();
	}

}
