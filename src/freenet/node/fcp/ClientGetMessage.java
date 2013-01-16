/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;

/**
 * ClientGet message.
 * 
 * Example:
 * 
 * ClientGet
 * IgnoreDS=false // true = ignore the datastore
 * DSOnly=false // true = only check the datastore, don't route (~= htl 0)
 * URI=KSK@sample.txt
 * Identifier=Request Number One
 * Verbosity=0 // no status, just tell us when it's done
 * ReturnType=direct // return all at once over the FCP connection
 * MaxSize=100 // maximum size of returned data 
 * MaxTempSize=1000 // maximum size of intermediary data
 * MaxRetries=100 // automatic retry supported as an option
 * PriorityClass=1 // priority class 1 = interactive
 * Persistence=reboot // continue until node is restarted; report progress while client is
 *    connected, including if it reconnects after losing connection
 * ClientToken=hello // returned in PersistentGet, a hint to the client, so the client 
 *    doesn't need to maintain its own state
 * IgnoreUSKDatehints=false // true = don't use USK datehints
 * EndMessage
 */
public class ClientGetMessage extends BaseDataCarryingMessage {

	public final static String NAME = "ClientGet";
	final boolean ignoreDS;
	final boolean dsOnly;
	final FreenetURI uri;
	final String identifier;
	final int verbosity;
	final short returnType;
	final short persistenceType;
	final long maxSize;
	final long maxTempSize;
	final int maxRetries;
	final short priorityClass;
	final File diskFile;
	final File tempFile;
	final String clientToken;
	final boolean global;
	final boolean binaryBlob;
	final String[] allowedMIMETypes;
	public boolean writeToClientCache;
	final String charset;
	final boolean filterData;
	final boolean realTimeFlag;
	final boolean ignoreUSKDatehints;
	private Bucket initialMetadata;
	private final long initialMetadataLength;
	
	// FIXME move these to the actual getter process
	static final short RETURN_TYPE_DIRECT = 0; // over FCP
	static final short RETURN_TYPE_NONE = 1; // not at all; to cache only; prefetch?
	static final short RETURN_TYPE_DISK = 2; // to a file
	static final short RETURN_TYPE_CHUNKED = 3; // FIXME implement: over FCP, as decoded

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public ClientGetMessage(SimpleFieldSet fs) throws MessageInvalidException {
		short defaultPriority;
		clientToken = fs.get("ClientToken");
		global = fs.getBoolean("Global", false);
		ignoreDS = fs.getBoolean("IgnoreDS", false);
		dsOnly = fs.getBoolean("DSOnly", false);
		identifier = fs.get("Identifier");
		allowedMIMETypes = fs.getAll("AllowedMIMETypes");
		filterData = fs.getBoolean("FilterData", false);
		charset = fs.get("Charset");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null, global);
		try {
			uri = new FreenetURI(fs.get("URI"));
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, e.getMessage(), identifier, global);
		}
		String verbosityString = fs.get("Verbosity");
		if(verbosityString == null)
			verbosity = 0;
		else {
			try {
				verbosity = Integer.parseInt(verbosityString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing Verbosity field: "+e.getMessage(), identifier, global);
			}
		}
		String returnTypeString = fs.get("ReturnType");
		returnType = parseReturnTypeFCP(returnTypeString);
		if(returnType == RETURN_TYPE_DIRECT) {
			diskFile = null;
			tempFile = null;
			// default just below FProxy
			defaultPriority = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		} else if(returnType == RETURN_TYPE_NONE) {
			diskFile = null;
			tempFile = null;
			defaultPriority = RequestStarter.PREFETCH_PRIORITY_CLASS;
		} else if(returnType == RETURN_TYPE_DISK) {
			defaultPriority = RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS;
			String filename = fs.get("Filename");
			if(filename == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing Filename", identifier, global);
			diskFile = new File(filename);
			String tempFilename = fs.get("TempFilename");
			if(tempFilename == null)
				tempFilename = filename + ".freenet-tmp";
			tempFile = new File(tempFilename);
			if(!diskFile.getAbsoluteFile().getParentFile().equals(tempFile.getAbsoluteFile().getParentFile()))
				throw new MessageInvalidException(ProtocolErrorMessage.FILENAME_AND_TEMP_FILENAME_MUST_BE_IN_SAME_DIR, null, identifier, global);
			if(tempFile.exists())
				throw new MessageInvalidException(ProtocolErrorMessage.DISK_TARGET_EXISTS, "Temp file exists", identifier, global);
			if(diskFile.exists())
				throw new MessageInvalidException(ProtocolErrorMessage.DISK_TARGET_EXISTS, null, identifier, global);
			try {
				// Check whether we can create it, so that we return an error early on.
				// Then delete it, as we have to rename over it anyway (atomic creation of a file does not guarantee
				// that it won't be replaced with a symlink).
				if(!(tempFile.createNewFile() || (tempFile.exists() && tempFile.canRead() && tempFile.canWrite())))
					throw new MessageInvalidException(ProtocolErrorMessage.COULD_NOT_CREATE_FILE, "Could not create temp file "+tempFile, identifier, global);
				tempFile.delete();
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.COULD_NOT_CREATE_FILE, e.getMessage(), identifier, global);
			}
		} else
			throw new MessageInvalidException(ProtocolErrorMessage.MESSAGE_PARSE_ERROR, "Unknown return-type", identifier, global);
		String maxSizeString = fs.get("MaxSize");
		if(maxSizeString == null)
			// default to unlimited
			maxSize = Long.MAX_VALUE;
		else {
			try {
				maxSize = Long.parseLong(maxSizeString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage(), identifier, global);
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
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage(), identifier, global);
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
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing MaxSize field: "+e.getMessage(), identifier, global);
			}
		}
		if(logMINOR)
			Logger.minor(this, "max retries="+maxRetries);
		String priorityString = fs.get("PriorityClass");
		if(priorityString == null) {
			// defaults to the one just below FProxy
			priorityClass = defaultPriority;
		} else {
			try {
				priorityClass = Short.parseShort(priorityString);
				if(!RequestStarter.isValidPriorityClass(priorityClass))
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid priority class "+priorityClass+" - range is "+RequestStarter.MINIMUM_PRIORITY_CLASS+" to "+RequestStarter.MAXIMUM_PRIORITY_CLASS, identifier, global);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing PriorityClass field: "+e.getMessage(), identifier, global);
			}
		}
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
			throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing Persistence field: "+persistenceString, identifier, global);
		}
		if(global && (persistenceType == ClientRequest.PERSIST_CONNECTION)) {
			throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "Global requests must be persistent", identifier, global);
		}
		writeToClientCache = fs.getBoolean("WriteToClientCache", persistenceType == ClientRequest.PERSIST_CONNECTION);
		binaryBlob = fs.getBoolean("BinaryBlob", false);
		realTimeFlag = fs.getBoolean("RealTimeFlag", false);
		initialMetadataLength = fs.getLong("InitialMetadata.DataLength", 0);
		ignoreUSKDatehints = fs.getBoolean("IgnoreUSKDatehints", false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("IgnoreDS", ignoreDS);
		fs.putSingle("URI", uri.toString(false, false));
		fs.put("FilterData", filterData);
		fs.putSingle("Charset", charset);
		fs.putSingle("Identifier", identifier);
		fs.put("Verbosity", verbosity);
		fs.putSingle("ReturnType", getReturnTypeString());
		fs.put("MaxSize", maxSize);
		fs.put("MaxTempSize", maxTempSize);
		fs.put("MaxRetries", maxRetries);
		fs.put("BinaryBlob", binaryBlob);
		return fs;
	}

	private String getReturnTypeString() {
		return returnTypeString(returnType);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) {
		handler.startClientGet(this);
	}

	public static String returnTypeString(short type) {
		switch(type) {
		case RETURN_TYPE_DIRECT:
			return "direct";
		case RETURN_TYPE_NONE:
			return "none";
		case RETURN_TYPE_DISK:
			return "disk";
		case RETURN_TYPE_CHUNKED:
			return "chunked";
		default:
			return Short.toString(type);
		}
	}

	short parseReturnTypeFCP(String string) throws MessageInvalidException {
		try {
			return parseReturnType(string);
		} catch (NumberFormatException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Unable to parse ReturnType "+string+" : "+e, identifier, global);
		}
	}
	
	public static short parseReturnType(String string) {
		if(string == null)
			return RETURN_TYPE_DIRECT;
		if(string.equalsIgnoreCase("direct"))
			return RETURN_TYPE_DIRECT;
		if(string.equalsIgnoreCase("none"))
			return RETURN_TYPE_NONE;
		if(string.equalsIgnoreCase("disk"))
			return RETURN_TYPE_DISK;
		if(string.equalsIgnoreCase("chunked"))
			return RETURN_TYPE_CHUNKED;
		return Short.parseShort(string);
	}

	public static short parseValidReturnType(String string) {
		short s = parseReturnType(string);
		if((s == RETURN_TYPE_DIRECT) || (s == RETURN_TYPE_NONE) || (s == RETURN_TYPE_DISK))
			return s;
		throw new IllegalArgumentException("Invalid or unsupported return type: "+returnTypeString(s));
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		uri.removeFrom(container);
		container.delete(diskFile);
		container.delete(tempFile);
		container.delete(this);
	}

	@Override
	long dataLength() {
		return initialMetadataLength;
	}

	@Override
	public void readFrom(InputStream is, BucketFactory bf, FCPServer server)
			throws IOException, MessageInvalidException {
		if(initialMetadataLength == 0) return;
		Bucket data;
		data = bf.makeBucket(initialMetadataLength);
		BucketTools.copyFrom(data, is, initialMetadataLength);
		// No need for synchronization here.
		initialMetadata = data;
	}

	@Override
	protected void writeData(OutputStream os) throws IOException {
		throw new UnsupportedOperationException();
	}

	public Bucket getInitialMetadata() {
		return initialMetadata;
	}

}
