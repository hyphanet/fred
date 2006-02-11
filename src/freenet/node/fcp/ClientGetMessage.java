package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.SimpleFieldSet;

/**
 * ClientGet message.
 * 
 * Example:
 * 
 * ClientGet
 * IgnoreDS=false // true = ignore the datastore
 * DSOnly=false // true = only check the datastore, don't route (~= htl 0)
 * URI=KSK@gpl.txt
 * Identifier=Request Number One
 * Verbosity=0 // no status, just tell us when it's done
 * ReturnType=direct // return all at once over the FCP connection
 * MaxSize=100 // maximum size of returned data 
 * MaxTempSize=1000 // maximum size of intermediary data
 * MaxRetries=100 // automatic retry supported as an option
 * PriorityClass=1 // priority class 1 = interactive
 * EndMessage
 */
public class ClientGetMessage extends FCPMessage {

	public final static String name = "ClientGet";
	final boolean ignoreDS;
	final boolean dsOnly;
	final FreenetURI uri;
	final String identifier;
	final int verbosity;
	final short returnType;
	final long maxSize;
	final long maxTempSize;
	final int maxRetries;
	final short priorityClass;
	final File diskFile;
	final File tempFile;
	
	// FIXME move these to the actual getter process
	static final short RETURN_TYPE_DIRECT = 0; // over FCP
	static final short RETURN_TYPE_NONE = 1; // not at all; to cache only; prefetch?
	static final short RETURN_TYPE_DISK = 2; // to a file
	static final short RETURN_TYPE_CHUNKED = 3; // FIXME implement: over FCP, as decoded
	
	public ClientGetMessage(SimpleFieldSet fs) throws MessageInvalidException {
		short defaultPriority;
		ignoreDS = Boolean.parseBoolean(fs.get("IgnoreDS"));
		dsOnly = Boolean.parseBoolean(fs.get("DSOnly"));
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null);
		try {
			uri = new FreenetURI(fs.get("URI"));
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.URI_PARSE_ERROR, e.getMessage(), identifier);
		}
		String verbosityString = fs.get("Verbosity");
		if(verbosityString == null)
			verbosity = 0;
		else {
			try {
				verbosity = Integer.parseInt(verbosityString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing Verbosity field: "+e.getMessage(), identifier);
			}
		}
		String returnTypeString = fs.get("ReturnType");
		if(returnTypeString == null || returnTypeString.equalsIgnoreCase("direct")) {
			returnType = RETURN_TYPE_DIRECT;
			diskFile = null;
			tempFile = null;
			// default just below fproxy
			defaultPriority = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		} else if(returnTypeString.equalsIgnoreCase("none")) {
			diskFile = null;
			tempFile = null;
			returnType = RETURN_TYPE_NONE;
			defaultPriority = RequestStarter.PREFETCH_PRIORITY_CLASS;
		} else if(returnTypeString.equalsIgnoreCase("disk")) {
			defaultPriority = RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS;
			returnType = RETURN_TYPE_DISK;
			String filename = fs.get("Filename");
			if(filename == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing Filename", identifier);
			diskFile = new File(filename);
			String tempFilename = fs.get("TempFilename");
			if(tempFilename == null)
				tempFilename = filename + ".freenet-tmp";
			tempFile = new File(tempFilename);
			if(!diskFile.getParentFile().equals(tempFile.getParentFile()))
				throw new MessageInvalidException(ProtocolErrorMessage.FILENAME_AND_TEMP_FILENAME_MUST_BE_IN_SAME_DIR, null, identifier);
			if(diskFile.exists())
				throw new MessageInvalidException(ProtocolErrorMessage.DISK_TARGET_EXISTS, null, identifier);
			try {
				if(!tempFile.createNewFile())
					throw new MessageInvalidException(ProtocolErrorMessage.COULD_NOT_CREATE_FILE, null, identifier);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.COULD_NOT_CREATE_FILE, e.getMessage(), identifier);
			}
		} else
			throw new MessageInvalidException(ProtocolErrorMessage.MESSAGE_PARSE_ERROR, "Unknown return-type", identifier);
		String maxSizeString = fs.get("MaxSize");
		if(maxSizeString == null)
			// default to unlimited
			maxSize = Long.MAX_VALUE;
		else {
			try {
				maxSize = Long.parseLong(maxSizeString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage(), identifier);
			}
		}
		String maxTempSizeString = fs.get("MaxTempSize");
		if(maxTempSizeString == null)
			// default to unlimited
			maxTempSize = Long.MAX_VALUE;
		else {
			try {
				maxTempSize = Long.parseLong(maxTempSizeString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage(), identifier);
			}
		}
		String maxRetriesString = fs.get("MaxRetries");
		if(maxRetriesString == null)
			// default to 0
			maxRetries = 0;
		else {
			try {
				maxRetries = Integer.parseInt(maxRetriesString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage(), identifier);
			}
		}
		String priorityString = fs.get("PriorityClass");
		if(priorityString == null) {
			// defaults to the one just below fproxy
			priorityClass = defaultPriority;
		} else {
			try {
				priorityClass = Short.parseShort(priorityString, 10);
				if(priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS || priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS)
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Valid priorities are from "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" to "+RequestStarter.MINIMUM_PRIORITY_CLASS, identifier);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing PriorityClass field: "+e.getMessage(), identifier);
			}
		}
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("IgnoreDS", Boolean.toString(ignoreDS));
		fs.put("URI", uri.toString(false));
		fs.put("Identifier", identifier);
		fs.put("Verbosity", Integer.toString(verbosity));
		fs.put("ReturnType", getReturnTypeString());
		fs.put("MaxSize", Long.toString(maxSize));
		fs.put("MaxTempSize", Long.toString(maxTempSize));
		fs.put("MaxRetries", Integer.toString(maxRetries));
		return fs;
	}

	private String getReturnTypeString() {
		if(returnType == RETURN_TYPE_DIRECT)
			return "direct";
		else
			throw new IllegalStateException("Unknown return type: "+returnType);
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) {
		handler.startClientGet(this);
	}

}
