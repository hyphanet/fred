/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class UnknownNodeIdentifierMessage extends FCPMessage {

	final String nodeIdentifier;
	final String identifier;
	
	public UnknownNodeIdentifierMessage(String id, String identifier) {
		this.nodeIdentifier = id;
		this.identifier = identifier;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("NodeIdentifier", nodeIdentifier);
		if(identifier != null)
			sfs.putSingle("Identifier", identifier);
		return sfs;
	}

	@Override
	public String getName() {
		return "UnknownNodeIdentifier";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "UnknownNodeIdentifier goes from server to client not the other way around", nodeIdentifier, false);
	}

}
