/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class ListPersistentRequestsMessage extends FCPMessage {

	static final String NAME = "ListPersistentRequests";
	
	public ListPersistentRequestsMessage(SimpleFieldSet fs) {
		// Do nothing
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	public String getName() {
		return NAME;
	}
	
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.getClient().queuePendingMessagesOnConnectionRestart(handler.outputHandler);
		handler.getClient().queuePendingMessagesFromRunningRequests(handler.outputHandler);
		if(handler.getClient().watchGlobal) {
			handler.server.globalClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler);
			handler.server.globalClient.queuePendingMessagesFromRunningRequests(handler.outputHandler);
		}
		handler.outputHandler.queue(new EndListPersistentRequestsMessage());
	}
	
}
