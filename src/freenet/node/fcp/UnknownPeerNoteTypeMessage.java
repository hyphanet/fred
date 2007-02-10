/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class UnknownPeerNoteTypeMessage extends FCPMessage {

	final int peerNoteType;
	
	public UnknownPeerNoteTypeMessage(int peerNoteType) {
		this.peerNoteType = peerNoteType;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("PeerNoteType", peerNoteType);
		return fs;
	}

	public String getName() {
		return "UnknownPeerNoteType";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "UnknownPeerNoteType goes from server to client not the other way around", null, false);
	}

}
