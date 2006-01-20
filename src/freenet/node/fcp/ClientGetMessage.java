package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.Node;
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
 * EndMessage
 */
public class ClientGetMessage extends FCPMessage {

	public final static String name = "ClientGet";
	final boolean ignoreDS;
	final boolean dsOnly;
	final FreenetURI uri;
	final String identifier;
	final int verbosity;
	final int returnType;
	final long maxSize;
	final long maxTempSize;
	final int maxRetries;
	
	// FIXME move these to the actual getter process
	static final int RETURN_TYPE_DIRECT = 0;
	
	public ClientGetMessage(SimpleFieldSet fs) throws MessageInvalidException {
		ignoreDS = Boolean.getBoolean(fs.get("IgnoreDS"));
		dsOnly = Boolean.getBoolean(fs.get("DSOnly"));
		try {
			uri = new FreenetURI(fs.get("URI"));
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.URI_PARSE_ERROR, e.getMessage());
		}
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier");
		String verbosityString = fs.get("Verbosity");
		if(verbosityString == null)
			verbosity = 0;
		else {
			try {
				verbosity = Integer.parseInt(verbosityString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing Verbosity field: "+e.getMessage());
			}
		}
		String returnTypeString = fs.get("ReturnType");
		if(returnTypeString == null || returnTypeString.equalsIgnoreCase("direct"))
			returnType = RETURN_TYPE_DIRECT;
		else
			throw new MessageInvalidException(ProtocolErrorMessage.MESSAGE_PARSE_ERROR, "Unknown return-type");
		String maxSizeString = fs.get("MaxSize");
		if(maxSizeString == null)
			// default to unlimited
			maxSize = Long.MAX_VALUE;
		else {
			try {
				maxSize = Long.parseLong(maxSizeString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage());
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
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage());
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
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage());
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
