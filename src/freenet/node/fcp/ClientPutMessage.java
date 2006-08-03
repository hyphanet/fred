package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;
import freenet.support.io.BucketFactory;
import freenet.support.io.FileBucket;

/**
 * 
 * ClientPut
 * URI=CHK@ // could as easily be an insertable SSK URI
 * Metadata.ContentType=text/html
 * Identifier=Insert-1 // identifier, as always
 * Verbosity=0 // just report when complete
 * MaxRetries=999999 // lots of retries
 * PriorityClass=1 // FProxy priority level
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
	final short persistenceType;
	final short uploadFromType;
	final boolean dontCompress;
	final String clientToken;
	final File origFilename;
	final boolean global;
	final FreenetURI redirectTarget;
	
	static final short UPLOAD_FROM_DIRECT = 0;
	static final short UPLOAD_FROM_DISK = 1;
	static final short UPLOAD_FROM_REDIRECT = 2;
	
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
		global = Fields.stringToBool(fs.get("Global"), false);
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
		getCHKOnly = Fields.stringToBool(fs.get("GetCHKOnly"), false);
		String priorityString = fs.get("PriorityClass");
		if(priorityString == null) {
			// defaults to the one just below FProxy
			priorityClass = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		} else {
			try {
				priorityClass = Short.parseShort(priorityString, 10);
				if((priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS) || (priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS))
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Valid priorities are from "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" to "+RequestStarter.MINIMUM_PRIORITY_CLASS, identifier);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing PriorityClass field: "+e.getMessage(), identifier);
			}
		}
		String uploadFrom = fs.get("UploadFrom");
		if((uploadFrom == null) || uploadFrom.equalsIgnoreCase("direct")) {
			uploadFromType = UPLOAD_FROM_DIRECT;
			String dataLengthString = fs.get("DataLength");
			if(dataLengthString == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Need DataLength on a ClientPut", identifier);
			try {
				dataLength = Long.parseLong(dataLengthString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing DataLength field: "+e.getMessage(), identifier);
			}
			this.origFilename = null;
			redirectTarget = null;
		} else if(uploadFrom.equalsIgnoreCase("disk")) {
			uploadFromType = UPLOAD_FROM_DISK;
			String filename = fs.get("Filename");
			if(filename == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field Filename", identifier);
			File f = new File(filename);
			if(!(f.exists() && f.isFile() && f.canRead()))
				throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, null, identifier);
			dataLength = f.length();
			FileBucket fileBucket = new FileBucket(f, true, false, false, false);
			this.bucket = fileBucket;
			this.origFilename = f;
			redirectTarget = null;
		} else if(uploadFrom.equalsIgnoreCase("redirect")) {
			uploadFromType = UPLOAD_FROM_REDIRECT;
			String target = fs.get("TargetURI");
			if(target == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "TargetURI missing but UploadFrom=redirect", identifier);
			try {
				redirectTarget = new FreenetURI(target);
			} catch (MalformedURLException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid TargetURI: "+e, identifier);
			}
			dataLength = 0;
			origFilename = null;
			bucket = null;
		} else
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "UploadFrom invalid or unrecognized: "+uploadFrom, identifier);
		dontCompress = Fields.stringToBool(fs.get("DontCompress"), false);
		String persistenceString = fs.get("Persistence");
		if((persistenceString == null) || persistenceString.equalsIgnoreCase("connection")) {
			// Default: persists until connection loss.
			persistenceType = ClientRequest.PERSIST_CONNECTION;
		} else if(persistenceString.equalsIgnoreCase("reboot")) {
			// Reports to client by name; persists over connection loss.
			// Not saved to disk, so dies on reboot.
			persistenceType = ClientRequest.PERSIST_REBOOT;
		} else if(persistenceString.equalsIgnoreCase("forever")) {
			// Same as reboot but saved to disk, persists forever.
			persistenceType = ClientRequest.PERSIST_FOREVER;
		} else {
			throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing Persistence field: "+persistenceString, identifier);
		}
		clientToken = fs.get("ClientToken");
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("URI", uri.toString());
		sfs.put("Identifier", identifier);
		sfs.put("Verbosity", Integer.toString(verbosity));
		sfs.put("MaxRetries", Integer.toString(maxRetries));
		sfs.put("Metadata.ContentType", contentType);
		sfs.put("ClientToken", clientToken);
		if(uploadFromType == UPLOAD_FROM_DIRECT) {
			sfs.put("UploadFrom", "direct");
			sfs.put("DataLength", Long.toString(dataLength));
		} else if(uploadFromType == UPLOAD_FROM_DISK) {
			sfs.put("UploadFrom", "disk");
			sfs.put("Filename", origFilename.getAbsolutePath());
			sfs.put("DataLength", Long.toString(dataLength));
		} else if(uploadFromType == UPLOAD_FROM_REDIRECT) {
			sfs.put("UploadFrom", "redirect");
			sfs.put("TargetURI", redirectTarget.toString());
		}
		sfs.put("GetCHKOnly", Boolean.toString(getCHKOnly));
		sfs.put("PriorityClass", Short.toString(priorityClass));
		sfs.put("PersistenceType", ClientRequest.persistenceTypeString(persistenceType));
		sfs.put("DontCompress", Boolean.toString(dontCompress));
		sfs.put("Global", Boolean.toString(global));
		return sfs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.startClientPut(this);
	}

	/**
	 * Get the length of the trailing field.
	 */
	long dataLength() {
		if(uploadFromType == UPLOAD_FROM_DIRECT)
			return dataLength;
		else return 0;
	}

	String getIdentifier() {
		return identifier;
	}

	Bucket createBucket(BucketFactory bf, long length, FCPServer server) throws IOException {
		if(persistenceType == ClientRequest.PERSIST_FOREVER) {
			return server.node.persistentTempBucketFactory.makeEncryptedBucket();
		} else {
			return super.createBucket(bf, length, server);
		}
	}

	public static String uploadFromString(short uploadFrom) {
		switch(uploadFrom) {
		case UPLOAD_FROM_DIRECT:
			return "direct";
		case UPLOAD_FROM_DISK:
			return "disk";
		case UPLOAD_FROM_REDIRECT:
			return "redirect";
		default:
			throw new IllegalArgumentException();
		}
	}
	
}
