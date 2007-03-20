/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ListPeersMessage extends FCPMessage {

	final boolean withMetadata;
	final boolean withVolatile;
	static final String name = "ListPeers";
	
	public ListPeersMessage(SimpleFieldSet fs) {
		withMetadata = Fields.stringToBool(fs.get("WithMetadata"), false);
		withVolatile = Fields.stringToBool(fs.get("WithVolatile"), false);
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	public String getName() {
		return name;
	}
	
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "ListPeers requires full access", null, false);
		}
		PeerNode[] nodes = node.getPeerNodes();
		for(int i = 0; i < nodes.length; i++) {
			PeerNode pn = nodes[i];
			handler.outputHandler.queue(new Peer(pn, withMetadata, withVolatile));
		}
		handler.outputHandler.queue(new EndListPeersMessage());
	}
	
}
