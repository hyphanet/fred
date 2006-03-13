package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.client.FailureCodeTracker;
import freenet.client.FetchException;
import freenet.client.InserterException;
import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class GetFailedMessage extends FCPMessage {

	final int code;
	final String codeDescription;
	final String shortCodeDescription;
	final String extraDescription;
	final FailureCodeTracker tracker;
	final boolean isFatal;
	final String identifier;
	
	public GetFailedMessage(FetchException e, String identifier) {
		Logger.minor(this, "Creating get failed from "+e+" for "+identifier, e);
		this.tracker = e.errorCodes;
		this.code = e.mode;
		this.codeDescription = FetchException.getMessage(code);
		this.extraDescription = e.extraMessage;
		this.shortCodeDescription = FetchException.getShortMessage(code);
		this.isFatal = e.isFatal();
		this.identifier = identifier;
	}

	/**
	 * Construct from a fieldset. Used in serialization of persistent requests.
	 * Will need to be made more tolerant of syntax errors if is used in an FCP
	 * client library. FIXME.
	 * @param useVerboseFields If true, read in verbose fields (CodeDescription
	 * etc), if false, reconstruct them from the error code.
	 * @throws MalformedURLException 
	 */
	public GetFailedMessage(SimpleFieldSet fs, boolean useVerboseFields) {
		identifier = fs.get("Identifier");
		if(identifier == null) throw new NullPointerException();
		code = Integer.parseInt(fs.get("Code"));
		
		if(useVerboseFields) {
			codeDescription = fs.get("CodeDescription");
			isFatal = Fields.stringToBool(fs.get("Fatal"), false);
			shortCodeDescription = fs.get("ShortCodeDescription");
		} else {
			codeDescription = FetchException.getMessage(code);
			isFatal = FetchException.isFatal(code);
			shortCodeDescription = FetchException.getShortMessage(code);
		}
		
		extraDescription = fs.get("ExtraDescription");
		SimpleFieldSet trackerSubset = fs.subset("Errors");
		if(trackerSubset != null) {
			tracker = new FailureCodeTracker(true, trackerSubset);
		} else {
			tracker = null;
		}
	}

	public SimpleFieldSet getFieldSet() {
		return getFieldSet(true);
	}
	
	/**
	 * Write to a SimpleFieldSet for storage or transmission.
	 * @param verbose If true, include fields which derive directly from static
	 * stuff on InserterException (and therefore can be omitted if talking to self
	 * or another node).
	 */
	public SimpleFieldSet getFieldSet(boolean verbose) {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.put("Code", Integer.toString(code));
		if(verbose)
			sfs.put("CodeDescription", codeDescription);
		if(extraDescription != null)
			sfs.put("ExtraDescription", extraDescription);
		if(verbose)
			sfs.put("Fatal", Boolean.toString(isFatal));
		if(tracker != null) {
			tracker.copyToFieldSet(sfs, "Errors.", verbose);
		}
		if(verbose)
			sfs.put("ShortCodeDescription", shortCodeDescription);
		sfs.put("Identifier", identifier);
		return sfs;
	}

	public String getName() {
		return "GetFailed";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "FetchError goes from server to client not the other way around", identifier);
	}

}
