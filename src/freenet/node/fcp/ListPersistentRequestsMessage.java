package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class ListPersistentRequestsMessage extends FCPMessage {

	static final String name = "ListPersistentRequests";
	
	public ListPersistentRequestsMessage(SimpleFieldSet fs) {
		// Do nothing
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(false);
	}
	
	public String getName() {
		return name;
	}
	
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.getClient().queuePendingMessagesOnConnectionRestart(handler.outputHandler);
		handler.getClient().queuePendingMessagesFromRunningRequests(handler.outputHandler);
	}
	
}
