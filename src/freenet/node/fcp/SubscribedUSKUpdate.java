package freenet.node.fcp;

import freenet.keys.USK;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SubscribedUSKUpdate extends FCPMessage {

	final String identifier;
	final long edition;
	final USK key;
	
	static final String name = "SubscribedUSKUpdate";
	
	public SubscribedUSKUpdate(String identifier, long l, USK key) {
		this.identifier = identifier;
		this.edition = l;
		this.key = key;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("Edition", Long.toString(edition));
		fs.put("URI", key.getURI().toString());
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SubscribedUSKUpdate goes from server to client not the other way around", identifier);
	}

}
