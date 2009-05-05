/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

public class ListPersistentRequestsMessage extends FCPMessage {

	static final String NAME = "ListPersistentRequests";
	
	public ListPersistentRequestsMessage(SimpleFieldSet fs) {
		// Do nothing
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	@Override
	public String getName() {
		return NAME;
	}
	
	@Override
	public void run(final FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		
		FCPClient rebootClient = handler.getRebootClient();
		
		rebootClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, null);
		rebootClient.queuePendingMessagesFromRunningRequests(handler.outputHandler, null);
		if(handler.getRebootClient().watchGlobal) {
			FCPClient globalRebootClient = handler.server.globalRebootClient;
			globalRebootClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, null);
			globalRebootClient.queuePendingMessagesFromRunningRequests(handler.outputHandler, null);
		}
		
		try {
			node.clientCore.clientContext.jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					FCPClient foreverClient = handler.getForeverClient(container);
					container.activate(foreverClient, 1);
					foreverClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, container);
					foreverClient.queuePendingMessagesFromRunningRequests(handler.outputHandler, container);
					if(handler.getRebootClient().watchGlobal) {
						FCPClient globalForeverClient = handler.server.globalForeverClient;
						globalForeverClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler, container);
						globalForeverClient.queuePendingMessagesFromRunningRequests(handler.outputHandler, container);
					}
					handler.outputHandler.queue(new EndListPersistentRequestsMessage());
					container.deactivate(foreverClient, 1);
				}
				
			}, NativeThread.HIGH_PRIORITY-1, false);
		} catch (DatabaseDisabledException e) {
			handler.outputHandler.queue(new EndListPersistentRequestsMessage());
		}
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
	
}
