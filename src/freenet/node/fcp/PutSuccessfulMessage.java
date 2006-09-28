/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PutSuccessfulMessage extends FCPMessage {

	public final String identifier;
	public final FreenetURI uri;
	
	public PutSuccessfulMessage(String identifier, FreenetURI uri) {
		this.identifier = identifier;
		this.uri = uri;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("URI", uri.toString());
		return fs;
	}

	public String getName() {
		return "PutSuccessful";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "InsertSuccessful goes from server to client not the other way around", identifier);
	}

}
