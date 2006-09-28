/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
	final short uploadFrom;
	final short persistenceType; 
	final File origFilename;
	final String mimeType;
	final boolean global;
	final FreenetURI targetURI;
	final long size;
	final String token;
	final boolean started;
	final int maxRetries;
	final String targetFilename;
	
	public PersistentPut(String identifier, FreenetURI uri, int verbosity, 
			short priorityClass, short uploadFrom, FreenetURI targetURI, 
			short persistenceType, File origFilename, String mimeType, 
			boolean global, long size, String clientToken, boolean started, int maxRetries, String targetFilename) {
		this.identifier = identifier;
		this.uri = uri;
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.uploadFrom = uploadFrom;
		this.targetURI = targetURI;
		this.persistenceType = persistenceType;
		this.origFilename = origFilename;
		this.mimeType = mimeType;
		this.global = global;
		this.size = size;
		this.token = clientToken;
		this.started = started;
		this.maxRetries = maxRetries;
		this.targetFilename = targetFilename;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("URI", uri.toString(false));
		fs.put("Verbosity", verbosity);
		fs.put("PriorityClass", priorityClass);
		fs.put("UploadFrom", ClientPutMessage.uploadFromString(uploadFrom));
		fs.put("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		if(origFilename != null)
			fs.put("Filename", origFilename.getAbsolutePath());
		if(targetURI != null)
			fs.put("TargetURI", targetURI.toString());
		if(mimeType != null)
			fs.put("Metadata.ContentType", mimeType);
		fs.put("Global", global);
		if(size != -1)
			fs.put("DataLength", size);
		if(token != null)
			fs.put("ClientToken", token);
		fs.put("Started", started);
		fs.put("MaxRetries", maxRetries);
		if(targetFilename != null)
			fs.put("TargetFilename", targetFilename);
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
