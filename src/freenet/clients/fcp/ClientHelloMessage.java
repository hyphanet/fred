/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 *  ClientHello
 *  Name=Toad's Test Client
 *  ExpectedVersion=0.7.0
 *  End
 */
public class ClientHelloMessage extends FCPMessage {

	public final static String NAME = "ClientHello";
	String clientName;
	String clientExpectedVersion;
	
	public ClientHelloMessage(SimpleFieldSet fs) throws MessageInvalidException {
		clientName = fs.get("Name");
		clientExpectedVersion = fs.get("ExpectedVersion");
		if(clientName == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "ClientHello must contain a Name field", null, false);
		if(clientExpectedVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "ClientHello must contain a ExpectedVersion field", null, false);
		// FIXME check the expected version
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("Name", clientName);
		sfs.putSingle("ExpectedVersion", clientExpectedVersion);
		return sfs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) {
		// We know the Hello is valid.
		FCPMessage msg = new NodeHelloMessage(handler.connectionIdentifier);
		handler.send(msg);
		handler.setClientName(clientName);
	}

}
