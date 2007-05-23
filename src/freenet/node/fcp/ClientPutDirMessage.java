/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
	final boolean earlyEncode;
	
	public ClientPutDirMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		global = Fields.stringToBool(fs.get("Global"), false);
		defaultName = fs.get("DefaultName");
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
		clientToken = fs.get("ClientToken");
		earlyEncode = Fields.stringToBool(fs.get("EarlyEncode"), false);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("URI", uri.toString());
		sfs.putSingle("Identifier", identifier);
		sfs.putSingle("Verbosity", Integer.toString(verbosity));
		sfs.putSingle("MaxRetries", Integer.toString(maxRetries));
		sfs.putSingle("ClientToken", clientToken);
		sfs.putSingle("GetCHKOnly", Boolean.toString(getCHKOnly));
		sfs.putSingle("PriorityClass", Short.toString(priorityClass));
		sfs.putSingle("PersistenceType", ClientRequest.persistenceTypeString(persistenceType));
		sfs.putSingle("DontCompress", Boolean.toString(dontCompress));
		sfs.putSingle("Global", Boolean.toString(global));
		sfs.putSingle("DefaultName", defaultName);
		return sfs;
	}

}
