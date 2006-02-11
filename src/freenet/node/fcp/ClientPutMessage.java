package freenet.node.fcp;

import java.io.File;
import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileBucket;

/**
 * 
 * ClientPut
 * URI=CHK@ // could as easily be an insertable SSK URI
 * Metadata.ContentType=text/html
 * Identifier=Insert-1 // identifier, as always
 * Verbosity=0 // just report when complete
 * MaxRetries=999999 // lots of retries
 * PriorityClass=1 // fproxy priority level
 * 
 * UploadFrom=direct // attached directly to this message
 * DataLength=100 // 100kB
 * or
 * UploadFrom=disk // upload a file from disk
 * Filename=/home/toad/something.html
 * Data
 * 
 * Neither IgnoreDS nor DSOnly make sense for inserts.
 */
public class ClientPutMessage extends DataCarryingMessage {

	public final static String name = "ClientPut";
	
	final FreenetURI uri;
	final String contentType;
	final long dataLength;
	final String identifier;
	final int verbosity;
	final int maxRetries;
	final boolean getCHKOnly;
	final short priorityClass;
	final boolean fromDisk;
	final boolean dontCompress;
	
	public ClientPutMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null);
		try {
			String u = fs.get("URI");
			if(u == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No URI", identifier);
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
		contentType = fs.get("Metadata.ContentType");
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
		getCHKOnly = Boolean.parseBoolean(fs.get("GetCHKOnly"));
		String priorityString = fs.get("PriorityClass");
		if(priorityString == null) {
			// defaults to the one just below fproxy
			priorityClass = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		} else {
			try {
				priorityClass = Short.parseShort(priorityString, 10);
				if(priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS || priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS)
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Valid priorities are from "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" to "+RequestStarter.MINIMUM_PRIORITY_CLASS, identifier);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing PriorityClass field: "+e.getMessage(), identifier);
			}
		}
		String uploadFrom = fs.get("UploadFrom");
		if(uploadFrom != null && uploadFrom.equalsIgnoreCase("disk")) {
			fromDisk = true;
			String filename = fs.get("Filename");
			if(filename == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field Filename", identifier);
			File f = new File(filename);
			if(!(f.exists() && f.isFile() && f.canRead()))
				throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, null, identifier);
			dataLength = f.length();
			FileBucket fileBucket = new FileBucket(f, true, false, false);
			this.bucket = fileBucket;
		} else {
			fromDisk = false;
			String dataLengthString = fs.get("DataLength");
			if(dataLengthString == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Need DataLength on a ClientPut", identifier);
			try {
				dataLength = Long.parseLong(dataLengthString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing DataLength field: "+e.getMessage(), identifier);
			}
		}
		dontCompress = Boolean.parseBoolean(fs.get("DontCompress"));
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("URI", uri.toString());
		sfs.put("Identifier", identifier);
		sfs.put("DataLength", Long.toString(dataLength));
		sfs.put("Verbosity", Integer.toString(verbosity));
		sfs.put("MaxRetries", Integer.toString(maxRetries));
		sfs.put("Metadata.ContentType", contentType);
		return sfs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.startClientPut(this);
	}

	long dataLength() {
		if(fromDisk) return 0;
		return dataLength;
	}

}
