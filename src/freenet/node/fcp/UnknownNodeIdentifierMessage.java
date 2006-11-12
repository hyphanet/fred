/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class UnknownNodeIdentifierMessage extends FCPMessage {

	final String nodeIdentifier;
	
	public UnknownNodeIdentifierMessage(String id) {
		this.nodeIdentifier = id;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("NodeIdentifier", nodeIdentifier);
		return sfs;
	}

	public String getName() {
		return "UnknownNodeIdentifier";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "UnknownNodeIdentifier goes from server to client not the other way around", nodeIdentifier);
	}

}
