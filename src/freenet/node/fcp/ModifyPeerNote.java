/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.DarknetPeerNode;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class ModifyPeerNote extends FCPMessage {

	static final String NAME = "ModifyPeerNote";
	
	final SimpleFieldSet fs;
	
	public ModifyPeerNote(SimpleFieldSet fs) {
		this.fs = fs;
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	public String getName() {
		return NAME;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "ModifyPeerNote requires full access", fs.get("Identifier"), false);
		}
		String nodeIdentifier = fs.get("NodeIdentifier");
		if( nodeIdentifier == null ) {
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Error: NodeIdentifier field missing", null, false);
		}
		PeerNode pn = node.getPeerNode(nodeIdentifier);
		if(pn == null) {
			FCPMessage msg = new UnknownNodeIdentifierMessage(nodeIdentifier);
			handler.outputHandler.queue(msg);
			return;
		}
		if(!(pn instanceof DarknetPeerNode)) {
			throw new MessageInvalidException(ProtocolErrorMessage.DARKNET_ONLY, "ModifyPeerNote only available for darknet peers", fs.get("Identifier"), false);
		}
		DarknetPeerNode dpn = (DarknetPeerNode) pn;
		int peerNoteType;
		try {
			peerNoteType = fs.getInt("PeerNoteType");
		} catch (FSParseException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Error parsing PeerNoteType field: "+e.getMessage(), null, false);
		}
		String encodedNoteText = fs.get("NoteText");
		if( encodedNoteText == null ) {
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Error: NoteText field missing", null, false);
		}
		String noteText;
		// **FIXME** this should be generalized for multiple peer notes per peer, after PeerNode is similarly generalized
		try {
			noteText = new String(Base64.decode(encodedNoteText));
		} catch (IllegalBase64Exception e) {
			Logger.error(this, "Bad Base64 encoding when decoding a FCP-received private darknet comment SimpleFieldSet", e);
			return;
		}
		if(peerNoteType == Node.PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT) {
			dpn.setPrivateDarknetCommentNote(noteText);
		} else {
			FCPMessage msg = new UnknownPeerNoteTypeMessage(peerNoteType);
			handler.outputHandler.queue(msg);
			return;
		}
		handler.outputHandler.queue(new PeerNote(nodeIdentifier, noteText, peerNoteType));
	}

}
