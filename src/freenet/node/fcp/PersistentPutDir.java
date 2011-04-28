/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.util.HashMap;

import com.db4o.ObjectContainer;

import freenet.client.async.ManifestElement;
import freenet.client.async.SimpleManifestPutter;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.DelayedFreeBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.NullBucket;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;
import freenet.support.io.PersistentTempFileBucket;

public class PersistentPutDir extends FCPMessage {

	static final String name = "PersistentPutDir";
	
	final String identifier;
	final FreenetURI uri;
	final int verbosity; 
	final short priorityClass;
	final short persistenceType; 
	final boolean global;
	private final HashMap<String, Object> manifestElements;
	final String defaultName;
	final String token;
	final boolean started;
	final int maxRetries;
	final boolean wasDiskPut;
	private final SimpleFieldSet cached;
	
	public PersistentPutDir(String identifier, FreenetURI uri, int verbosity, short priorityClass,
	        short persistenceType, boolean global, String defaultName, HashMap<String, Object> manifestElements,
	        String token, boolean started, int maxRetries, boolean wasDiskPut, ObjectContainer container) {
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
		this.wasDiskPut = wasDiskPut;
		cached = generateFieldSet(container);
	}

	private SimpleFieldSet generateFieldSet(ObjectContainer container) {
		SimpleFieldSet fs = new SimpleFieldSet(false); // false because this can get HUGE
		fs.putSingle("Identifier", identifier);
		fs.putSingle("URI", uri.toString(false, false));
		fs.put("Verbosity", verbosity);
		fs.putSingle("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		fs.put("PriorityClass", priorityClass);
		fs.putSingle("Global", Boolean.toString(global));
		fs.putSingle("PutDirType", wasDiskPut ? "disk" : "complex");
		SimpleFieldSet files = new SimpleFieldSet(false);
		// Flatten the hierarchy, it can be reconstructed on restarting.
		// Storing it directly would be a PITA.
		// FIXME/RESOLVE: The new BaseManifestPutter's container mode does not hold the origin data,
		//                 after composing the PutHandlers (done in BaseManifestPutter), they are 'lost':
		//                 A resumed half done container put can not get the complete file list from BaseManifestPutter.
		//                 Is it really necessary to include the file list here?
		ManifestElement[] elements = SimpleManifestPutter.flatten(manifestElements);
		fs.putSingle("DefaultName", defaultName);
		for(int i=0;i<elements.length;i++) {
			String num = Integer.toString(i);
			ManifestElement e = elements[i];
			String mimeOverride = e.getMimeTypeOverride();
			SimpleFieldSet subset = new SimpleFieldSet(false);
			FreenetURI tempURI = e.getTargetURI();
			subset.putSingle("Name", e.getName());
			if(tempURI != null) {
				subset.putSingle("UploadFrom", "redirect");
				subset.putSingle("TargetURI", tempURI.toString());
			} else {
				// Deactivate the top, not the middle.
				// Deactivating the middle can cause big problems.
				Bucket origData = e.getData();
				Bucket data = origData;
				boolean deactivate = false;
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					deactivate = !container.ext().isActive(data);
				if(deactivate)
					container.activate(data, 1);
				if(data instanceof DelayedFreeBucket) {
					data = ((DelayedFreeBucket)data).getUnderlying();
				}
				subset.put("DataLength", e.getSize());
				if(mimeOverride != null)
					subset.putSingle("Metadata.ContentType", mimeOverride);
				// What to do with the bucket?
				// It is either a persistent encrypted bucket or a file bucket ...
				if(data == null) {
					Logger.error(this, "Bucket already freed: "+e.getData()+" for "+e+" for "+e.getName()+" for "+identifier);
				} else if(data instanceof FileBucket) {
					subset.putSingle("UploadFrom", "disk");
					subset.putSingle("Filename", ((FileBucket)data).getFile().getPath());
				} else if (data instanceof PaddedEphemerallyEncryptedBucket || data instanceof NullBucket || data instanceof PersistentTempFileBucket) {
					subset.putSingle("UploadFrom", "direct");
				} else {
					throw new IllegalStateException("Don't know what to do with bucket: "+data);
				}
				if(deactivate)
					container.deactivate(origData, 1);
			}
			files.put(num, subset);
		}
		files.put("Count", elements.length);
		fs.put("Files", files);
		if(token != null)
			fs.putSingle("ClientToken", token);
		fs.put("Started", started);
		fs.put("MaxRetries", maxRetries);
		return fs;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return cached;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentPut goes from server to client not the other way around", identifier, global);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.activate(uri, 5);
		uri.removeFrom(container);
		// manifestElements will be removed by ClientPutDir.freeData, not our problem.
		container.activate(cached, Integer.MAX_VALUE);
		cached.removeFrom(container);
		container.delete(this);
	}

}
