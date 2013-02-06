/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InsertContext;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.compress.InvalidCompressionCodecException;
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
 * FileHash=021349568329403123
 * Data
 * 
 * Neither IgnoreDS nor DSOnly make sense for inserts.
 */
public class ClientPutMessage extends DataCarryingMessage {

	public final static String NAME = "ClientPut";
	
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
	/** The hash of the file you want the node to deal with.
	 *  it is MANDATORY to do DDA operations and should be computed like that:
	 *  
	 *  Base64Encode(SHA256( Handler.connectionIdentifer + ClientPutMessage.identifier + content)) 
	 */
	final String fileHash;
	final boolean dontCompress;
	final String clientToken;
	final File origFilename;
	final boolean global;
	final FreenetURI redirectTarget;
	/** Filename (hint for the final filename) */
	final String targetFilename;
	final boolean earlyEncode;
	final boolean binaryBlob;
	final boolean canWriteClientCache;
	final String compressorDescriptor;
	final boolean forkOnCacheable;
	final int extraInsertsSingleBlock;
	final int extraInsertsSplitfileHeaderBlock;
	final InsertContext.CompatibilityMode compatibilityMode;
	final byte[] overrideSplitfileCryptoKey;
	final boolean localRequestOnly;
	final boolean realTimeFlag;
	final long metadataThreshold;
	
	public static final short UPLOAD_FROM_DIRECT = 0;
	public static final short UPLOAD_FROM_DISK = 1;
	public static final short UPLOAD_FROM_REDIRECT = 2;
	
	public ClientPutMessage(SimpleFieldSet fs) throws MessageInvalidException {
		String fnam = null;
		identifier = fs.get("Identifier");
		binaryBlob = fs.getBoolean("BinaryBlob", false);
		global = fs.getBoolean("Global", false);
		localRequestOnly = fs.getBoolean("LocalRequestOnly", false);
		String s = fs.get("CompatibilityMode");
		InsertContext.CompatibilityMode cmode = null;
		if(s == null)
			cmode = InsertContext.CompatibilityMode.COMPAT_CURRENT;
		else {
			try {
				cmode = InsertContext.CompatibilityMode.valueOf(s);
			} catch (IllegalArgumentException e) {
				try {
					cmode = InsertContext.CompatibilityMode.values()[Integer.parseInt(s)];
				} catch (NumberFormatException e1) {
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid CompatibilityMode (not a name and not a number)", identifier, global);
				} catch (ArrayIndexOutOfBoundsException e1) {
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid CompatibilityMode (not a valid number)", identifier, global);
				}
			}
		}
		compatibilityMode = cmode;
		s = fs.get("OverrideSplitfileCryptoKey");
		if(s == null)
			overrideSplitfileCryptoKey = null;
		else
			try {
				overrideSplitfileCryptoKey = HexUtil.hexToBytes(s);
			} catch (NumberFormatException e1) {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid splitfile crypto key (not hex)", identifier, global);
			} catch (IndexOutOfBoundsException e1) {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid splitfile crypto key (too short)", identifier, global);
			}
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null, global);
		try {
			if(binaryBlob)
				uri = new FreenetURI("CHK@");
			else {
				String u = fs.get("URI");
				if(u == null)
					throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No URI", identifier, global);
				FreenetURI uu = new FreenetURI(u);
				String[] metas = uu.getAllMetaStrings();
				if(metas != null && metas.length == 1) {
					fnam = metas[0];
					uu = uu.setMetaString(null);
				} // if >1, will fail later
				uri = uu;
			}
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
		contentType = fs.get("Metadata.ContentType");
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
		getCHKOnly = fs.getBoolean("GetCHKOnly", false);
		String priorityString = fs.get("PriorityClass");
		if(priorityString == null) {
			// defaults to the one just below FProxy
			priorityClass = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		} else {
			try {
				priorityClass = Short.parseShort(priorityString);
				if(!RequestStarter.isValidPriorityClass(priorityClass))
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid priority class "+priorityClass+" - range is "+RequestStarter.MINIMUM_PRIORITY_CLASS+" to "+RequestStarter.MAXIMUM_PRIORITY_CLASS, identifier, global);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing PriorityClass field: "+e.getMessage(), identifier, global);
			}
		}
		// We do *NOT* check that FileHash is valid here for backward compatibility... and to make the override work
		this.fileHash = fs.get(ClientPutBase.FILE_HASH);
		String uploadFrom = fs.get("UploadFrom");
		if((uploadFrom == null) || uploadFrom.equalsIgnoreCase("direct")) {
			uploadFromType = UPLOAD_FROM_DIRECT;
			String dataLengthString = fs.get("DataLength");
			if(dataLengthString == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Need DataLength on a ClientPut", identifier, global);
			try {
				dataLength = Long.parseLong(dataLengthString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing DataLength field: "+e.getMessage(), identifier, global);
			}
			this.origFilename = null;
			redirectTarget = null;
		} else if(uploadFrom.equalsIgnoreCase("disk")) {
			uploadFromType = UPLOAD_FROM_DISK;
			String filename = fs.get("Filename");
			if(filename == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field Filename", identifier, global);
			File f = new File(filename);
			if(!(f.exists() && f.isFile() && f.canRead()))
				throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, null, identifier, global);
			dataLength = f.length();
			FileBucket fileBucket = new FileBucket(f, true, false, false, false, false);
			this.bucket = fileBucket;
			this.origFilename = f;
			redirectTarget = null;
			if(fnam == null)
				fnam = origFilename.getName();
		} else if(uploadFrom.equalsIgnoreCase("redirect")) {
			uploadFromType = UPLOAD_FROM_REDIRECT;
			String target = fs.get("TargetURI");
			if(target == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "TargetURI missing but UploadFrom=redirect", identifier, global);
			try {
				redirectTarget = new FreenetURI(target);
			} catch (MalformedURLException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid TargetURI: "+e, identifier, global);
			}
			dataLength = 0;
			origFilename = null;
			bucket = null;
		} else
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "UploadFrom invalid or unrecognized: "+uploadFrom, identifier, global);
		dontCompress = fs.getBoolean("DontCompress", false);
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
		canWriteClientCache = fs.getBoolean("WriteToClientCache", false);
		clientToken = fs.get("ClientToken");
		String f = fs.get("TargetFilename");
		if(f != null)
			fnam = f;
		if(fnam != null && fnam.indexOf('/') > -1) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "TargetFilename must not contain slashes", identifier, global);
		}
		if(fnam != null && fnam.length() == 0) {
			fnam = null; // Deliberate override to tell us not to create one.
		}
		if(uri.getRoutingKey() == null && !uri.isKSK())
			targetFilename = fnam;
		else
			targetFilename = null;
		earlyEncode = fs.getBoolean("EarlyEncode", false);
		String codecs = fs.get("Codecs");
		if (codecs != null) {
			COMPRESSOR_TYPE[] ca;
			try {
				ca = COMPRESSOR_TYPE.getCompressorsArrayNoDefault(codecs);
			} catch (InvalidCompressionCodecException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, e.getMessage(), identifier, global);
			}
			if (ca == null) 
				codecs = null;
		}
		compressorDescriptor = codecs;
		if(fs.get("ForkOnCacheable") != null)
			forkOnCacheable = fs.getBoolean("ForkOnCacheable", false);
		else
			forkOnCacheable = Node.FORK_ON_CACHEABLE_DEFAULT;
		extraInsertsSingleBlock = fs.getInt("ExtraInsertsSingleBlock", HighLevelSimpleClientImpl.EXTRA_INSERTS_SINGLE_BLOCK);
		extraInsertsSplitfileHeaderBlock = fs.getInt("ExtraInsertsSplitfileHeaderBlock", HighLevelSimpleClientImpl.EXTRA_INSERTS_SPLITFILE_HEADER);
		realTimeFlag = fs.getBoolean("RealTimeFlag", false);
		metadataThreshold = fs.getLong("MetadataThreshold", -1);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("URI", uri.toString());
		sfs.putSingle("Identifier", identifier);
		sfs.put("Verbosity", verbosity);
		sfs.put("MaxRetries", maxRetries);
		sfs.putSingle("Metadata.ContentType", contentType);
		sfs.putSingle("ClientToken", clientToken);
		if(uploadFromType == UPLOAD_FROM_DIRECT) {
			sfs.putSingle("UploadFrom", "direct");
			sfs.put("DataLength", dataLength);
		} else if(uploadFromType == UPLOAD_FROM_DISK) {
			sfs.putSingle("UploadFrom", "disk");
			sfs.putSingle("Filename", origFilename.getAbsolutePath());
			sfs.put("DataLength", dataLength);
		} else if(uploadFromType == UPLOAD_FROM_REDIRECT) {
			sfs.putSingle("UploadFrom", "redirect");
			sfs.putSingle("TargetURI", redirectTarget.toString());
		}
		sfs.put("GetCHKOnly", getCHKOnly);
		sfs.put("PriorityClass", priorityClass);
		sfs.putSingle("PersistenceType", ClientRequest.persistenceTypeString(persistenceType));
		sfs.put("DontCompress", dontCompress);
		if (compressorDescriptor != null)
			sfs.putSingle("Codecs", compressorDescriptor);
		sfs.put("Global", global);
		sfs.put("BinaryBlob", binaryBlob);
		return sfs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.startClientPut(this);
	}

	/**
	 * Get the length of the trailing field.
	 */
	@Override
	long dataLength() {
		if(uploadFromType == UPLOAD_FROM_DIRECT)
			return dataLength;
		else return -1;
	}

	@Override
	String getIdentifier() {
		return identifier;
	}

	@Override
	Bucket createBucket(BucketFactory bf, long length, FCPServer server) throws IOException {
		if(persistenceType == ClientRequest.PERSIST_FOREVER) {
			return server.core.persistentTempBucketFactory.makeBucket(length);
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

	@Override
	boolean isGlobal() {
		return global;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		uri.removeFrom(container);
		container.delete(origFilename);
		if(redirectTarget != null)
			redirectTarget.removeFrom(container);
	}

	public void freeData(ObjectContainer container) {
		if(bucket == null) {
			if(dataLength() <= 0)
				return; // Okay.
			Logger.error(this, "bucket is null on "+this+" - freed twice?", new Exception("error"));
			return;
		}
		if(persistenceType == ClientRequest.PERSIST_FOREVER)
			container.activate(bucket, 5);
		bucket.free();
		if(persistenceType == ClientRequest.PERSIST_FOREVER)
			bucket.removeFrom(container);
	}
	
}
