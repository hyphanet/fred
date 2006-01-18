package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * 
 * ClientPut
 * URI=CHK@ // could as easily be an insertable SSK URI
 * Metadata.ContentType=text/html
 * DataLength=19000 // hex for 100kB
 * Identifier=Insert-1 // identifier, as always
 * Verbosity=0 // just report when complete
 * MaxRetries=999999 // lots of retries
 * Data
 * 
 * Neither IgnoreDS nor DSOnly make sense for inserts.
 */
public class ClientPutMessage extends DataCarryingMessage {

	final FreenetURI uri;
	final String contentType;
	final long dataLength;
	final String identifier;
	final int verbosity;
	final int maxRetries;
	
	public ClientPutMessage(SimpleFieldSet fs) throws MessageInvalidException {
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
				verbosity = Integer.parseInt(verbosityString, 16);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing Verbosity field: "+e.getMessage());
			}
		}
		String dataLengthString = fs.get("DataLength");
		if(dataLengthString == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Need DataLength on a ClientPut");
		try {
			dataLength = Long.parseLong(dataLengthString, 16);
		} catch (NumberFormatException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing DataLength field: "+e.getMessage());
		}
		contentType = fs.get("Metadata.ContentType");
		String maxRetriesString = fs.get("MaxRetries");
		if(maxRetriesString == null)
			// default to 0
			maxRetries = 0;
		else {
			try {
				maxRetries = Integer.parseInt(maxRetriesString, 16);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage());
			}
		}
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("URI", uri.toString());
		sfs.put("Identifier", identifier);
		sfs.put("DataLength", Long.toHexString(dataLength));
		sfs.put("Verbosity", Integer.toHexString(verbosity));
		sfs.put("MaxRetries", Integer.toHexString(maxRetries));
		sfs.put("Metadata.ContentType", contentType);
		return sfs;
	}

	public String getName() {
		return "ClientPut";
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.startClientPut(this);
	}

	long dataLength() {
		return dataLength;
	}

}
