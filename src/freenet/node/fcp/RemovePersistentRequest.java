package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

/**
 * Client telling node to remove a (completed or not) persistent request.
 */
public class RemovePersistentRequest extends FCPMessage {

	final static String name = "RemovePersistentRequest";
	
	final String identifier;
	final boolean global;
	
	public RemovePersistentRequest(SimpleFieldSet fs) throws MessageInvalidException {
		this.identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Must have Identifier", null);
		this.global = Fields.stringToBool(fs.get("Global"), false);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.put("Identifier", identifier);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		FCPClient client = global ? handler.server.globalClient : handler.getClient();
		client.removeByIdentifier(identifier, true);
	}

}
