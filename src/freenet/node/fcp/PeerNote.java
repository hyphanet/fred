/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.Base64;
import freenet.support.SimpleFieldSet;

public class PeerNote extends FCPMessage {
	static final String name = "PeerNote";

	final String noteText;
	final int peerNoteType;
	final String nodeIdentifier;
	final String identifier;

	public PeerNote(String nodeIdentifier, String noteText, int peerNoteType, String identifier) {
		this.nodeIdentifier = nodeIdentifier;
		this.noteText = noteText;
		this.peerNoteType = peerNoteType;
		this.identifier = identifier;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("NodeIdentifier", nodeIdentifier);
		fs.put("PeerNoteType", peerNoteType);
		fs.putSingle("NoteText", Base64.encodeUTF8(noteText, true));
		if(identifier != null)
			fs.putSingle("Identifier", identifier);
		return fs;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PeerNote goes from server to client not the other way around", identifier, false);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
