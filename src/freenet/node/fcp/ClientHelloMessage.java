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
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "ClientHello must contain a Name field");
		if(clientExpectedVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "ClientHello must contain a ExpectedVersion field");
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
		handler.setClientName(clientName);
		FCPMessage msg = new NodeHelloMessage();
		handler.outputHandler.queue(msg);
	}

}
