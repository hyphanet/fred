/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class EndListPeerNotesMessage extends FCPMessage {

	final String nodeIdentifier;
	static final String name = "EndListPeerNotes";
	
	public EndListPeerNotesMessage(String id) {
		this.nodeIdentifier = id;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("NodeIdentifier", nodeIdentifier);
		return sfs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "EndListPeerNotes goes from server to client not the other way around", null, false);
	}

}
