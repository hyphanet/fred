package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class GetResultsMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	final static String name = "GetResults";
	
	public GetResultsMessage(SimpleFieldSet fs) {
		this.identifier = fs.get("Identifier");
		this.global = Fields.stringToBool(fs.get("Global"), false);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("Identifier", identifier);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		ClientRequest req;
		if(global)
			req = handler.server.globalClient.getRequest(identifier);
		else
			req = (ClientRequest) 
				handler.getClient().getRequest(identifier);
		if(req == null) {
			ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, false, null, identifier);
			handler.outputHandler.queue(msg);
		} else {
			req.sendPendingMessages(handler.outputHandler, true, true);
		}
	}

}
