package freenet.node.fcp;

import freenet.client.FailureCodeTracker;
import freenet.client.InserterException;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PutFailedMessage extends FCPMessage {

	final int code;
	final String codeDescription;
	final String extraDescription;
	final String codeShortDescription;
	final FailureCodeTracker tracker;
	final FreenetURI expectedURI;
	final String identifier;
	final boolean isFatal;
	
	public PutFailedMessage(InserterException e, String identifier) {
		this.code = e.getMode();
		this.codeDescription = InserterException.getMessage(code);
		this.codeShortDescription = InserterException.getShortMessage(code);
		this.extraDescription = e.extra;
		this.tracker = e.errorCodes;
		this.expectedURI = e.uri;
		this.identifier = identifier;
		this.isFatal = e.isFatal();
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("Code", Integer.toString(code));
		fs.put("CodeDescription", codeDescription);
		if(extraDescription != null)
			fs.put("ExtraDescription", extraDescription);
		if(tracker != null) {
			tracker.copyToFieldSet(fs, "Errors.");
		}
		fs.put("Fatal", Boolean.toString(isFatal));
		fs.put("ShortCodeDescription", codeShortDescription);
		if(expectedURI != null)
			fs.put("ExpectedURI", expectedURI.toString());
		return fs;
	}

	public String getName() {
		return "PutFailed";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PutFailed goes from server to client not the other way around");
	}

}
