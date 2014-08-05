/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class UnknownPeerNoteTypeMessage extends FCPMessage {

	final int peerNoteType;
	final String identifier;
	
	public UnknownPeerNoteTypeMessage(int peerNoteType, String identifier) {
		this.peerNoteType = peerNoteType;
		this.identifier = identifier;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("PeerNoteType", peerNoteType);
		if(identifier != null)
			fs.putSingle("Identifier", identifier);
		return fs;
	}

	@Override
	public String getName() {
		return "UnknownPeerNoteType";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "UnknownPeerNoteType goes from server to client not the other way around", identifier, false);
	}

}
