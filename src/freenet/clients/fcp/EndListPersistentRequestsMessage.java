/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class EndListPersistentRequestsMessage extends FCPMessage {

	static final String name = "EndListPersistentRequests";
	private final String listRequestIdentifier;

	public EndListPersistentRequestsMessage(String listRequestIdentifier) {
		this.listRequestIdentifier = listRequestIdentifier;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet simpleFieldSet = new SimpleFieldSet(true);
		simpleFieldSet.putSingle("Identifier", listRequestIdentifier);
		return simpleFieldSet;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "EndListPersistentRequests goes from server to client not the other way around", null, false);
	}

}
