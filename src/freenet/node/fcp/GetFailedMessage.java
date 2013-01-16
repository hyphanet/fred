/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.FailureCodeTracker;
import freenet.client.FetchException;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;

public class GetFailedMessage extends FCPMessage {

	final int code;
	final String codeDescription;
	final String shortCodeDescription;
	final String extraDescription;
	final FailureCodeTracker tracker;
	final boolean isFatal;
	final String identifier;
	final boolean global;
	final long expectedDataLength;
	final String expectedMimeType;
	final boolean finalizedExpected;
	final FreenetURI redirectURI;
	       
        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public GetFailedMessage(FetchException e, String identifier, boolean global) {
		if(logMINOR)
			Logger.minor(this, "Creating get failed from "+e+" for "+identifier, e);
		this.tracker = e.errorCodes;
		this.code = e.mode;
		this.codeDescription = FetchException.getMessage(code);
		this.extraDescription = e.extraMessage;
		this.shortCodeDescription = FetchException.getShortMessage(code);
		this.isFatal = e.isFatal();
		this.identifier = identifier;
		this.global = global;
		this.expectedDataLength = e.expectedSize;
		this.expectedMimeType = e.getExpectedMimeType();
		this.finalizedExpected = e.finalizedSize();
		this.redirectURI = e.newURI;
	}

	/**
	 * Construct from a fieldset. Used in serialization of persistent requests.
	 * Will need to be made more tolerant of syntax errors if is used in an FCP
	 * client library. FIXME.
	 * @param useVerboseFields If true, read in verbose fields (CodeDescription
	 * etc), if false, reconstruct them from the error code.
	 */
	public GetFailedMessage(SimpleFieldSet fs, boolean useVerboseFields) throws MalformedURLException {
		identifier = fs.get("Identifier");
		if(identifier == null) throw new NullPointerException();
		code = Integer.parseInt(fs.get("Code"));
		
		if(useVerboseFields) {
			codeDescription = fs.get("CodeDescription");
			isFatal = fs.getBoolean("Fatal", false);
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
		expectedMimeType = fs.get("ExpectedMimeType");
		finalizedExpected = fs.getBoolean("FinalizedExpected", false);
		String s = fs.get("ExpectedDataLength");
		if(s != null) {
			expectedDataLength = Long.parseLong(s);
		} else
			expectedDataLength = -1;
		s = fs.get("RedirectURI");
		if(s != null)
			this.redirectURI = new FreenetURI(s);
		else
			this.redirectURI = null;
		this.global = fs.getBoolean("Global", false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return getFieldSet(true);
	}
	
	/**
	 * Write to a SimpleFieldSet for storage or transmission.
	 * @param verbose If true, include fields which derive directly from static
	 * stuff on InsertException (and therefore can be omitted if talking to self
	 * or another node).
	 */
	public SimpleFieldSet getFieldSet(boolean verbose) {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.put("Code", code);
		if(verbose)
			sfs.putSingle("CodeDescription", codeDescription);
		if(extraDescription != null)
			sfs.putSingle("ExtraDescription", extraDescription);
		if(verbose)
			sfs.put("Fatal", isFatal);
		if(tracker != null) {
			sfs.tput("Errors", tracker.toFieldSet(verbose));
		}
		if(verbose)
			sfs.putSingle("ShortCodeDescription", shortCodeDescription);
		sfs.putSingle("Identifier", identifier);
		if(expectedDataLength > -1) {
			sfs.put("ExpectedDataLength", expectedDataLength);
		}
		if(expectedMimeType != null)
			sfs.putSingle("ExpectedMetadata.ContentType", expectedMimeType);
		if(finalizedExpected)
			sfs.putSingle("FinalizedExpected", "true");
		if(redirectURI != null)
			sfs.putSingle("RedirectURI", redirectURI.toString(false, false));
		return sfs;
	}

	@Override
	public String getName() {
		return "GetFailed";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "GetFailed goes from server to client not the other way around", identifier, global);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		if(redirectURI != null) {
			container.activate(redirectURI, 5);
			redirectURI.removeFrom(container); // URI belongs to the parent which is also being removed.
		}
		if(tracker != null) {
			container.activate(tracker, 5);
			tracker.removeFrom(container);
		}
		container.delete(this);
	}
	
	public String getShortFailedMessage() {
		return shortCodeDescription;
	}
	
	public String getLongFailedMessage() {
		if(extraDescription != null)
			return shortCodeDescription + ": " + extraDescription;
		else
			return shortCodeDescription;
	}

}
