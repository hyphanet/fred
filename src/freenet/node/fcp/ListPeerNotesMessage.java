/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ListPeerNotesMessage extends FCPMessage {

	static final String name = "ListPeerNotes";
	final SimpleFieldSet fs;
	
	public ListPeerNotesMessage(SimpleFieldSet fs) {
		this.fs = fs;
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	public String getName() {
		return name;
	}
	
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		String nodeIdentifier = fs.get("NodeIdentifier");
		PeerNode pn = node.getPeerNode(nodeIdentifier);
		if(pn == null) {
			FCPMessage msg = new UnknownNodeIdentifierMessage(nodeIdentifier);
			handler.outputHandler.queue(msg);
			return;
		}
		// **FIXME** this should be generalized for multiple peer notes per peer, after PeerNode is similarly generalized
		String noteText = pn.getPrivateDarknetCommentNote();
		handler.outputHandler.queue(new PeerNote(nodeIdentifier, noteText, Node.PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT));
		handler.outputHandler.queue(new EndListPeerNotesMessage(nodeIdentifier));
	}
	
}
