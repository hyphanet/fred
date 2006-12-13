/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.util.HashMap;

import freenet.client.async.ManifestElement;
import freenet.client.async.SimpleManifestPutter;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;

public class PersistentPutDir extends FCPMessage {

	static final String name = "PersistentPutDir";
	
	final String identifier;
	final FreenetURI uri;
	final int verbosity; 
	final short priorityClass;
	final short persistenceType; 
	final boolean global;
	private final HashMap manifestElements;
	final String defaultName;
	final String token;
	final boolean started;
	final int maxRetries;
	
	public PersistentPutDir(String identifier, FreenetURI uri, int verbosity, 
			short priorityClass, short persistenceType, boolean global,
			String defaultName, HashMap manifestElements, String token, boolean started, int maxRetries) {
		this.identifier = identifier;
		this.uri = uri;
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.persistenceType = persistenceType;
		this.global = global;
		this.defaultName = defaultName;
		this.manifestElements = manifestElements;
		this.token = token;
		this.started = started;
		this.maxRetries = maxRetries;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Identifier", identifier);
		fs.put("URI", uri.toString(false, false));
		fs.put("Verbosity", Integer.toString(verbosity));
		fs.put("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		fs.put("PriorityClass", Short.toString(priorityClass));
		fs.put("Global", Boolean.toString(global));
		SimpleFieldSet files = new SimpleFieldSet();
		// Flatten the hierarchy, it can be reconstructed on restarting.
		// Storing it directly would be a PITA.
		ManifestElement[] elements = SimpleManifestPutter.flatten(manifestElements);
		fs.put("DefaultName", defaultName);
		for(int i=0;i<elements.length;i++) {
			String num = Integer.toString(i);
			ManifestElement e = elements[i];
			String mimeOverride = e.getMimeTypeOverride();
			SimpleFieldSet subset = new SimpleFieldSet();
			FreenetURI tempURI = e.getTargetURI();
			subset.put("Name", e.getName());
			if(tempURI != null) {
				subset.put("UploadFrom", "redirect");
				subset.put("TargetURI", tempURI.toString());
			} else {
				Bucket data = e.getData();
				subset.put("DataLength", Long.toString(e.getSize()));
				if(mimeOverride != null)
					subset.put("Metadata.ContentType", mimeOverride);
				// What to do with the bucket?
				// It is either a persistent encrypted bucket or a file bucket ...
				if(data instanceof FileBucket) {
					subset.put("UploadFrom", "disk");
					subset.put("Filename", ((FileBucket)data).getFile().getPath());
				} else if((data instanceof PaddedEphemerallyEncryptedBucket) || (data == null)) {
					subset.put("UploadFrom", "direct");
				} else {
					throw new IllegalStateException("Don't know what to do with bucket: "+data);
				}
			}
			files.put(num, subset);
		}
		fs.put("Files", files);
		if(token != null)
			fs.put("ClientToken", token);
		fs.put("Started", Boolean.toString(started));
		fs.put("MaxRetries", maxRetries);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentPut goes from server to client not the other way around", identifier, global);
	}


	
}
