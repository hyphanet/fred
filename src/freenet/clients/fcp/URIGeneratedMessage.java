/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class URIGeneratedMessage extends FCPMessage {

	private final FreenetURI uri;
	private final String identifier;
	private final boolean global;
	
	public URIGeneratedMessage(FreenetURI uri, String identifier, boolean global) {
		this.uri = uri;
		this.identifier = identifier;
		this.global = global;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("URI", uri.toString());
		fs.putSingle("Identifier", identifier);
		fs.put("Global", global);
		return fs;
	}

	@Override
	public String getName() {
		return "URIGenerated";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "URIGenerated goes from server to client not the other way around", identifier, false);
	}

}
