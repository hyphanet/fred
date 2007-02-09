/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class IdentifierCollisionMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	
	public IdentifierCollisionMessage(String id, boolean global) {
		this.identifier = id;
		this.global = global;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.putSingle("Identifier", identifier);
		if(global) sfs.putSingle("Global", "true");
		return sfs;
	}

	public String getName() {
		return "IdentifierCollision";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "IdentifierCollision goes from server to client not the other way around", identifier, global);
	}

}
