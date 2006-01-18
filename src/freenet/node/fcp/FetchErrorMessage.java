package freenet.node.fcp;

import freenet.client.FailureCodeTracker;
import freenet.client.FetchException;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class FetchErrorMessage extends FCPMessage {

	final int code;
	final String codeDescription;
	final String extraDescription;
	final FailureCodeTracker tracker;
	final boolean isFatal;
	
	public FetchErrorMessage(FCPConnectionHandler handler, FetchException e, String identifier) {
		this.tracker = e.errorCodes;
		this.code = e.mode;
		this.codeDescription = FetchException.getMessage(code);
		this.extraDescription = e.extraMessage;
		this.isFatal = e.isFatal();
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("Code", Integer.toHexString(code));
		sfs.put("CodeDescription", codeDescription);
		if(extraDescription != null)
			sfs.put("ExtraDescription", extraDescription);
		sfs.put("Fatal", Boolean.toString(isFatal));
		if(tracker != null) {
			tracker.copyToFieldSet(sfs, "Errors.");
		}
		return sfs;
	}

	public String getName() {
		return "FetchError";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "FetchError goes from server to client not the other way around");
	}

}
