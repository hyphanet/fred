package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class StartedCompressionMessage extends FCPMessage {

	final String identifier;
	
	final int codec;
	
	public StartedCompressionMessage(String identifier, int codec) {
		this.identifier = identifier;
		this.codec = codec;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("Codec", Integer.toString(codec));
		return fs;
	}

	public String getName() {
		return "StartedCompression";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "StartedCompression goes from server to client not the other way around", identifier);
	}

}
