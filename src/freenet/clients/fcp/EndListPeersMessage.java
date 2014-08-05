/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class EndListPeersMessage extends FCPMessage {

	static final String name = "EndListPeers";
	private final String identifier;
	
	public EndListPeersMessage(String identifier) {
		this.identifier = identifier;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
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
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "EndListPeers goes from server to client not the other way around", null, false);
	}

}
