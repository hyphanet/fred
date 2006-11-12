/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class ModifyPeerNote extends FCPMessage {

	static final String name = "ModifyPeerNote";
	
	final SimpleFieldSet fs;
	
	public ModifyPeerNote(SimpleFieldSet fs) {
		this.fs = fs;
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet();
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		String nodeIdentifier = fs.get("NodeIdentifier");
		PeerNode pn = node.getPeerNode(nodeIdentifier);
		if(pn == null) {
			FCPMessage msg = new UnknownNodeIdentifierMessage(nodeIdentifier);
			handler.outputHandler.queue(msg);
			return;
		}
		int peerNoteType;
		try {
			peerNoteType = fs.getInt("PeerNoteType");
		} catch (FSParseException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Error parsing PeerNoteType field: "+e.getMessage(), null);
		}
		String encodedNoteText = fs.get("NoteText");
		String noteText;
		// **FIXME** this should be generalized for multiple peer notes per peer, after PeerNode is similarly generalized
		try {
			noteText = new String(Base64.decode(encodedNoteText));
		} catch (IllegalBase64Exception e) {
			Logger.error(this, "Bad Base64 encoding when decoding a FCP-received private darknet comment SimpleFieldSet", e);
			return;
		}
		if(peerNoteType == Node.PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT) {
			pn.setPrivateDarknetCommentNote(noteText);
		} else {
			FCPMessage msg = new UnknownPeerNoteTypeMessage(peerNoteType);
			handler.outputHandler.queue(msg);
			return;
		}
		handler.outputHandler.queue(new PeerNote(nodeIdentifier, noteText, peerNoteType));
	}

}
