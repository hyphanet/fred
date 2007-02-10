/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SSKKeypairMessage extends FCPMessage {

	private final FreenetURI insertURI;
	private final FreenetURI requestURI;
	private final String identifier;
	
	public SSKKeypairMessage(FreenetURI insertURI, FreenetURI requestURI, String identifier) {
		this.insertURI = insertURI;
		this.requestURI = requestURI;
		this.identifier = identifier;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("InsertURI", insertURI.toString());
		sfs.putSingle("RequestURI", requestURI.toString());
		if(identifier != null) // is optional on these two only
			sfs.putSingle("Identifier", identifier);
		return sfs;
	}

	public String getName() {
		return "SSKKeypair";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SSKKeypair goes from server to client not the other way around", identifier, false);
	}
	
	

}
