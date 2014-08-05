/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class EndListPeerNotesMessage extends FCPMessage {

	final String nodeIdentifier;
	static final String name = "EndListPeerNotes";
	private String identifier;
	
	public EndListPeerNotesMessage(String id, String identifier) {
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
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "EndListPeerNotes goes from server to client not the other way around", null, false);
	}

}
