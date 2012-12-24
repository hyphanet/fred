/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;

import com.db4o.ObjectContainer;

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
	final boolean started;
	final int maxRetries;
	final boolean binaryBlob;
	final long maxSize;
	final boolean realTime;
	
	public PersistentGet(String identifier, FreenetURI uri, int verbosity, 
			short priorityClass, short returnType, short persistenceType, 
			File targetFile, File tempFile, String clientToken, boolean global, boolean started, int maxRetries, boolean binaryBlob, long maxSize, boolean realTime) {
		this.identifier = identifier;
		this.uri = uri;
		// This has been seen in practice (bug #3606), lets try to get an earlier stack trace...
		if(uri == null) throw new NullPointerException();
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.returnType = returnType;
		this.persistenceType = persistenceType;
		this.targetFile = targetFile;
		this.tempFile = tempFile;
		this.clientToken = clientToken;
		this.global = global;
		this.started = started;
		this.maxRetries = maxRetries;
		this.binaryBlob = binaryBlob;
		this.maxSize = maxSize;
		this.realTime = realTime;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.putSingle("URI", uri.toString(false, false));
		fs.put("Verbosity", verbosity);
		fs.putSingle("ReturnType", ClientGetMessage.returnTypeString(returnType));
		fs.putSingle("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		// FIXME PersistenceType is backward compatibility cruft, everything else uses Persistence
		fs.putSingle("PersistenceType", ClientRequest.persistenceTypeString(persistenceType));
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			fs.putSingle("Filename", targetFile.getAbsolutePath());
			fs.putSingle("TempFilename", tempFile.getAbsolutePath());
		}
		fs.put("PriorityClass", priorityClass);
		if(clientToken != null)
			fs.putSingle("ClientToken", clientToken);
		fs.put("Global", global);
		fs.put("Started", started);
		fs.put("MaxRetries", maxRetries);
		fs.put("BinaryBlob", binaryBlob);
		fs.put("MaxSize", maxSize);
		fs.put("RealTime", realTime);
		return fs;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentGet goes from server to client not the other way around", identifier, global);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.activate(uri, 5);
		uri.removeFrom(container);
		container.activate(targetFile, 5);
		container.delete(targetFile);
		container.activate(tempFile, 5);
		container.delete(tempFile);
		container.delete(this);
	}

}
