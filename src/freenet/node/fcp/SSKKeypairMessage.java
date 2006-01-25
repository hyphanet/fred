package freenet.node.fcp;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SSKKeypairMessage extends FCPMessage {

	private final FreenetURI insertURI;
	private final FreenetURI requestURI;
	
	public SSKKeypairMessage(FreenetURI insertURI, FreenetURI requestURI) {
		this.insertURI = insertURI;
		this.requestURI = requestURI;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("InsertURI", insertURI.toString());
		sfs.put("RequestURI", requestURI.toString());
		return sfs;
	}

	public String getName() {
		return "SSKKeypair";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SSKKeypair goes from server to client not the other way around");
	}
	
	

}
