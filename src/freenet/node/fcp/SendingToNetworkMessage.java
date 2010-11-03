package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SendingToNetworkMessage extends FCPMessage {

	public static final String NAME = "SendingToNetwork";
	final String identifier;
	final boolean global;
	
	public SendingToNetworkMessage(String id, boolean global2) {
		this.identifier = id;
		this.global = global2;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.put("Global", global);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		// Not possible
	}

}
