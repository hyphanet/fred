package freenet.node.fcp;

import java.io.File;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PersistentPut extends FCPMessage {

	static final String name = "PersistentPut";
	
	final String identifier;
	final FreenetURI uri;
	final int verbosity; 
	final short priorityClass;
	final boolean fromDisk;
	final short persistenceType; 
	final File origFilename;
	final String mimeType;
	final boolean global;
	
	public PersistentPut(String identifier, FreenetURI uri, int verbosity, 
			short priorityClass, boolean fromDisk, short persistenceType, 
			File origFilename, String mimeType, boolean global) {
		this.identifier = identifier;
		this.uri = uri;
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.fromDisk = fromDisk;
		this.persistenceType = persistenceType;
		this.origFilename = origFilename;
		this.mimeType = mimeType;
		this.global = global;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.put("Identifier", identifier);
		fs.put("URI", uri.toString(false));
		fs.put("Verbosity", Integer.toString(verbosity));
		fs.put("PriorityClass", Short.toString(priorityClass));
		fs.put("UploadFrom", (fromDisk ? "disk" : "direct"));
		fs.put("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		if(origFilename != null)
			fs.put("Filename", origFilename.getAbsolutePath());
		if(mimeType != null)
			fs.put("Metadata.ContentType", mimeType);
		fs.put("PriorityClass", Short.toString(priorityClass));
		fs.put("Global", Boolean.toString(global));
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentPut goes from server to client not the other way around", identifier);
	}

}
