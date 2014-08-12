/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;

import freenet.client.FailureCodeTracker;
import freenet.client.FetchException;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.StorageFormatException;
import freenet.support.SimpleFieldSet;

public class GetFailedMessage extends FCPMessage implements Serializable {

    private static final long serialVersionUID = 1L;
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
	
	protected GetFailedMessage() {
	    // For serialization.
	    code = 0;
	    codeDescription = null;
	    shortCodeDescription = null;
	    extraDescription = null;
	    tracker = null;
	    isFatal = false;
	    identifier = null;
	    global = false;
	    expectedDataLength = 0;
	    expectedMimeType = null;
	    finalizedExpected = false;
	    redirectURI = null;
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

	public String getShortFailedMessage() {
		return shortCodeDescription;
	}
	
	public String getLongFailedMessage() {
		if(extraDescription != null)
			return shortCodeDescription + ": " + extraDescription;
		else
			return shortCodeDescription;
	}
	
	static final int VERSION = 1;

    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(VERSION);
        // Do not write anything redundant.
        dos.writeInt(code);
        writePossiblyNull(extraDescription, dos);
        dos.writeBoolean(finalizedExpected);
        writePossiblyNull(redirectURI == null ? null : redirectURI.toString(), dos);
    }
    
    public GetFailedMessage(DataInputStream dis, RequestIdentifier reqID,
            long expectedSize, String expectedType) throws StorageFormatException, IOException {
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version in GetFailedMessage");
        code = dis.readInt();
        if(!FetchException.isErrorCode(code))
            throw new StorageFormatException("Bad error code");
        this.isFatal = FetchException.isFatal(code);
        this.codeDescription = FetchException.getMessage(code);
        this.shortCodeDescription = FetchException.getShortMessage(code);
        this.extraDescription = readPossiblyNull(dis);
        this.finalizedExpected = dis.readBoolean();
        String s = readPossiblyNull(dis);
        if(s != null) {
            try {
                redirectURI = new FreenetURI(s);
            } catch (MalformedURLException e) {
                throw new StorageFormatException("Bad redirect URI in GetFailedMessage: "+e);
            }
        } else {
            redirectURI = null;
        }
        this.global = reqID.globalQueue;
        this.identifier = reqID.identifier;
        this.tracker = null; // Don't save that level of detail.
        this.expectedDataLength = expectedSize;
        this.expectedMimeType = expectedType;
        
    }

    private String readPossiblyNull(DataInputStream dis) throws IOException {
        if(dis.readBoolean()) {
            return dis.readUTF();
        } else {
            return null;
        }
    }

    private void writePossiblyNull(String s, DataOutputStream dos) throws IOException {
        if(s != null) {
            dos.writeBoolean(true);
            dos.writeUTF(s);
        } else {
            dos.writeBoolean(false);
        }
    }

}
