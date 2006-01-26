package freenet.node.fcp;

import freenet.client.FailureCodeTracker;
import freenet.client.FetchException;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class GetFailedMessage extends FCPMessage {

	final int code;
	final String codeDescription;
	final String shortCodeDescription;
	final String extraDescription;
	final FailureCodeTracker tracker;
	final boolean isFatal;
	final String identifier;
	
	public GetFailedMessage(FCPConnectionHandler handler, FetchException e, String identifier) {
		this.tracker = e.errorCodes;
		this.code = e.mode;
		this.codeDescription = FetchException.getMessage(code);
		this.extraDescription = e.extraMessage;
		this.shortCodeDescription = FetchException.getShortMessage(code);
		this.isFatal = e.isFatal();
		this.identifier = identifier;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("Code", Integer.toString(code));
		sfs.put("CodeDescription", codeDescription);
		if(extraDescription != null)
			sfs.put("ExtraDescription", extraDescription);
		sfs.put("Fatal", Boolean.toString(isFatal));
		if(tracker != null) {
			tracker.copyToFieldSet(sfs, "Errors.");
		}
		sfs.put("ShortCodeDescription", shortCodeDescription);
		sfs.put("Identifier", identifier);
		return sfs;
	}

	public String getName() {
		return "GetFailed";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "FetchError goes from server to client not the other way around");
	}

}
