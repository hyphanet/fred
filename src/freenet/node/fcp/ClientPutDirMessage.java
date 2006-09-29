package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.RequestStarter;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

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
	
	public ClientPutDirMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		defaultName = fs.get("DefaultName");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null);
		try {
			String u = fs.get("URI");
			if(u == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No URI", identifier);
			FreenetURI uu = new FreenetURI(fs.get("URI"));
			// Client is allowed to put a slash at the end if it wants to, but this is discouraged.
			String[] meta = uu.getAllMetaStrings();
			if(meta != null && meta.length == 1 && meta[0].length() == 0)
				uu = uu.setMetaString(null);
			uri = uu;
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
		sfs.put("ClientToken", clientToken);
		sfs.put("GetCHKOnly", Boolean.toString(getCHKOnly));
		sfs.put("PriorityClass", Short.toString(priorityClass));
		sfs.put("PersistenceType", ClientRequest.persistenceTypeString(persistenceType));
		sfs.put("DontCompress", Boolean.toString(dontCompress));
		sfs.put("Global", Boolean.toString(global));
		sfs.put("DefaultName", defaultName);
		return sfs;
	}

}
