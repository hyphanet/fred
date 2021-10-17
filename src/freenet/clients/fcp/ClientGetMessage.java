/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;

import freenet.clients.fcp.ClientGet.ReturnType;
import freenet.clients.fcp.ClientRequest.Persistence;
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
	final ReturnType returnType;
	final Persistence persistence;
	final long maxSize;
	final long maxTempSize;
	final int maxRetries;
	final short priorityClass;
	final File diskFile;
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
		if(returnType == ReturnType.DIRECT) {
			diskFile = null;
			// default just below FProxy
			defaultPriority = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		} else if(returnType == ReturnType.NONE) {
			diskFile = null;
			defaultPriority = RequestStarter.PREFETCH_PRIORITY_CLASS;
		} else if(returnType == ReturnType.DISK) {
			defaultPriority = RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS;
			String filename = fs.get("Filename");
			if(filename == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing Filename", identifier, global);
			diskFile = new File(filename);
			if(diskFile.exists())
				throw new MessageInvalidException(ProtocolErrorMessage.DISK_TARGET_EXISTS, null, identifier, global);
			try {
				// Check whether we can create a temp file in the target directory.
			    File temp = File.createTempFile(diskFile.getName(), ".freenet-tmp", diskFile.getParentFile());
			    temp.delete();
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
				if(maxSize < 0)
				    throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Maximum size must be positive", identifier, global);
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
                if(maxTempSize < 0)
                    throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Maximum temp size must be positive", identifier, global);
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
				if(maxRetries < -1) throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Max retries must be -1 or larger", identifier, global);
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
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid priority class "+priorityClass+" - range is "+RequestStarter.PAUSED_PRIORITY_CLASS+" to "+RequestStarter.MAXIMUM_PRIORITY_CLASS, identifier, global);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing PriorityClass field: "+e.getMessage(), identifier, global);
			}
		}
		String persistenceString = fs.get("Persistence");
		persistence = Persistence.parseOrThrow(persistenceString, identifier, global);
		if(global && (persistence == Persistence.CONNECTION)) {
			throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "Global requests must be persistent", identifier, global);
		}
		writeToClientCache = fs.getBoolean("WriteToClientCache", persistence == Persistence.CONNECTION);
		binaryBlob = fs.getBoolean("BinaryBlob", false);
		realTimeFlag = fs.getBoolean("RealTimeFlag", false);
		initialMetadataLength = fs.getLong("InitialMetadata.DataLength", 0);
		if(initialMetadataLength < 0)
		    throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid data length for initial metadata", identifier, global);
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
		return returnType.toString();
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) {
		handler.startClientGet(this);
	}

	ReturnType parseReturnTypeFCP(String string) throws MessageInvalidException {
		try {
		    if(string == null) return ReturnType.DIRECT;
		    return ReturnType.valueOf(string.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Unable to parse ReturnType "+string+" : "+e, identifier, global);
		}
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
