package freenet.node.fcp;

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * Sent by the node to a client when it asks for a list of current requests.
 * PersistentGet
 * End
 */
public class PersistentGet extends FCPMessage {

	static final String name = "PersistentGet";
	
	final String identifier;
	final FreenetURI uri;
	final int verbosity;
	final short priorityClass;
	final short returnType;
	final short persistenceType;
	final File targetFile;
	final File tempFile;
	final String clientToken;
	final boolean global;
	
	public PersistentGet(String identifier, FreenetURI uri, int verbosity, 
			short priorityClass, short returnType, short persistenceType, 
			File targetFile, File tempFile, String clientToken, boolean global) {
		this.identifier = identifier;
		this.uri = uri;
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.returnType = returnType;
		this.persistenceType = persistenceType;
		this.targetFile = targetFile;
		this.tempFile = tempFile;
		this.clientToken = clientToken;
		this.global = global;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.put("Identifier", identifier);
		fs.put("URI", uri.toString(false));
		fs.put("Verbosity", Integer.toString(verbosity));
		fs.put("ReturnType", ClientGetMessage.returnTypeString(returnType));
		fs.put("PersistenceType", ClientRequest.persistenceTypeString(persistenceType));
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			fs.put("Filename", targetFile.getAbsolutePath());
			fs.put("TempFilename", tempFile.getAbsolutePath());
		}
		fs.put("PriorityClass", Short.toString(priorityClass));
		if(clientToken != null)
			fs.put("ClientToken", clientToken);
		fs.put("Global", Boolean.toString(global));
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentGet goes from server to client not the other way around", identifier);
	}

}
