package freenet.node.fcp;

import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class GenerateSSKMessage extends FCPMessage {

	static final String name = "GenerateSSK";
	final String identifier;
	
	GenerateSSKMessage(SimpleFieldSet fs) {
		identifier = fs.get("Identifier");
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(false);
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
    	InsertableClientSSK key = InsertableClientSSK.createRandom(node.random);
    	FreenetURI insertURI = key.getInsertURI();
    	FreenetURI requestURI = key.getURI();
    	SSKKeypairMessage msg = new SSKKeypairMessage(insertURI, requestURI, identifier);
    	handler.outputHandler.queue(msg);
	}

}
