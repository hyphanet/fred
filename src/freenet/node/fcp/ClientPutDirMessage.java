/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.client.async.ManifestPutter;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InsertContext;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.HexUtil;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

/**
 * Put a directory, rather than a file.
 * Base class.
 * 
 * Two forms: ClientPutDiskDir and ClientPutComplexDir
 *
 * Both share:
 * Identifier=<identifier>
 * Verbosity=<verbosity as ClientPut>
 * MaxRetries=<max retries as ClientPut>
 * PriorityClass=<priority class>
 * URI=<target URI>
 * GetCHKOnly=<GetCHKOnly as ClientPut>
 * DontCompress=<DontCompress as ClientPut>
 * ClientToken=<ClientToken as ClientPut>
 * Persistence=<Persistence as ClientPut>
 * Global=<Global as ClientPut>
 */
public abstract class ClientPutDirMessage extends BaseDataCarryingMessage {
	// Some subtypes of this (ClientPutComplexDirMessage) may carry a payload.

	final String identifier;
	final FreenetURI uri;
	final int verbosity;
	final int maxRetries;
	final boolean getCHKOnly;
	final short priorityClass;
	final short persistenceType;
	final boolean dontCompress;
	final String clientToken;
	final boolean global;
	final String defaultName;
	final boolean earlyEncode;
	final boolean canWriteClientCache;
	final String compressorDescriptor;
	public boolean forkOnCacheable;
	final int extraInsertsSingleBlock;
	final int extraInsertsSplitfileHeaderBlock;
	final InsertContext.CompatibilityMode compatibilityMode;
	final byte[] overrideSplitfileCryptoKey;
	final boolean localRequestOnly;
	final boolean realTimeFlag;
	final short manifestPutterType;
	final String targetFilename;
	
	public ClientPutDirMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		global = Fields.stringToBool(fs.get("Global"), false);
		defaultName = fs.get("DefaultName");
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
		localRequestOnly = fs.getBoolean("LocalRequestOnly", false);
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null, global);
		try {
			String u = fs.get("URI");
			if(u == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No URI", identifier, global);
			FreenetURI uu = new FreenetURI(fs.get("URI"));
			// Client is allowed to put a slash at the end if it wants to, but this is discouraged.
			String[] meta = uu.getAllMetaStrings();
			if(meta != null && meta.length == 1 && meta[0].length() == 0)
				uu = uu.setMetaString(null);
			uri = uu;
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
		getCHKOnly = Fields.stringToBool(fs.get("GetCHKOnly"), false);
		String priorityString = fs.get("PriorityClass");
		if(priorityString == null) {
			// defaults to the one just below FProxy
			priorityClass = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		} else {
			try {
				priorityClass = Short.parseShort(priorityString, 10);
				if((priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS) || (priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS))
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Valid priorities are from "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" to "+RequestStarter.MINIMUM_PRIORITY_CLASS, identifier, global);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing PriorityClass field: "+e.getMessage(), identifier, global);
			}
		}
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
			throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing Persistence field: "+persistenceString, identifier, global);
		}
		canWriteClientCache = fs.getBoolean("WriteToClientCache", false);
		clientToken = fs.get("ClientToken");
		targetFilename = fs.get("TargetFilename");
		earlyEncode = Fields.stringToBool(fs.get("EarlyEncode"), false);
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
		String manifestPutter = fs.get("ManifestPutter");
		short manifestType;
		if(persistenceType == ClientRequest.PERSIST_FOREVER)
			// Unfortunately default isn't known to work with persistent inserts yet.
			// It might work but is probably leaky and buggy. 
			// FIXME Make default work with persistent site inserts then change this.
			// FIXME Make default work with overrideSplitfileCryptoKey - doesn't it already???
			manifestType = ManifestPutter.MANIFEST_SIMPLEPUTTER;
		else
			manifestType = ManifestPutter.MANIFEST_DEFAULTPUTTER;
		if("simple".equalsIgnoreCase(manifestPutter)) {
			manifestType = ManifestPutter.MANIFEST_SIMPLEPUTTER;
		} else if("default".equalsIgnoreCase(manifestPutter)) {
			manifestType = ManifestPutter.MANIFEST_DEFAULTPUTTER;
		} else if(manifestPutter != null) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid ManifestPutter value: "+manifestPutter, identifier, global);
		}
		manifestPutterType = manifestType;
		if(manifestPutterType != ManifestPutter.MANIFEST_SIMPLEPUTTER && (persistenceType != ClientRequest.PERSIST_CONNECTION && persistenceType != ClientRequest.PERSIST_REBOOT)) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Only Connection or Reboot persistenace can only be used with the default ManifestPutter", identifier, global);
		}
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("URI", uri.toString());
		sfs.putSingle("Identifier", identifier);
		sfs.put("Verbosity", verbosity);
		sfs.put("MaxRetries", maxRetries);
		sfs.putSingle("ClientToken", clientToken);
		sfs.put("GetCHKOnly", getCHKOnly);
		sfs.put("PriorityClass", priorityClass);
		sfs.putSingle("PersistenceType", ClientRequest.persistenceTypeString(persistenceType));
		sfs.put("DontCompress", dontCompress);
		if (compressorDescriptor != null)
			sfs.putSingle("Codecs", compressorDescriptor);
		sfs.put("Global", global);
		sfs.putSingle("DefaultName", defaultName);
		sfs.putSingle("ManifestPutter", ManifestPutter.manifestPutterTypeString(manifestPutterType));
		return sfs;
	}

}
