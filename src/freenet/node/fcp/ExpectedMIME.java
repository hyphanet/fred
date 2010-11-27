package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class ExpectedMIME extends FCPMessage {

	final String identifier;
	final boolean global;
	final String expectedMIME;
	
	ExpectedMIME(String identifier, boolean global, String expectedMIME) {
		this.identifier = identifier;
		this.global = global;
		this.expectedMIME = expectedMIME;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putOverwrite("Identifier", identifier);
		fs.put("Global", global);
		fs.putOverwrite("Metadata.ContentType", expectedMIME);
		return fs;
	}

	@Override
	public String getName() {
		return "ExpectedMIME";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		// Not supported
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
