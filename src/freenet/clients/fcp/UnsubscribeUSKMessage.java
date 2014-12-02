package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class UnsubscribeUSKMessage extends FCPMessage {

	public static final String NAME = "UnsubscribeUSK";
	private final String identifier;

	public UnsubscribeUSKMessage(SimpleFieldSet fs) throws MessageInvalidException {
		this.identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier!", null, false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.unsubscribeUSK(identifier);
	}

}
