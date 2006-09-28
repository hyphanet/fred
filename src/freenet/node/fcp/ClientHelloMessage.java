/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 *  ClientHello
 *  Name=Toad's Test Client
 *  ExpectedVersion=0.7.0
 *  End
 */
public class ClientHelloMessage extends FCPMessage {

	public final static String name = "ClientHello";
	String clientName;
	String clientExpectedVersion;
	
	public ClientHelloMessage(SimpleFieldSet fs) throws MessageInvalidException {
		clientName = fs.get("Name");
		clientExpectedVersion = fs.get("ExpectedVersion");
		if(clientName == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "ClientHello must contain a Name field", null);
		if(clientExpectedVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "ClientHello must contain a ExpectedVersion field", null);
		// FIXME check the expected version
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("Name", clientName);
		sfs.put("ExpectedVersion", clientExpectedVersion);
		return sfs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) {
		// We know the Hello is valid.
		FCPMessage msg = new NodeHelloMessage(node);
		handler.outputHandler.queue(msg);
		handler.setClientName(clientName);
	}

}
