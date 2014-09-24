/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.util.HashMap;

import freenet.client.InsertContext;
import freenet.client.async.BaseManifestPutter;
import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.crypt.EncryptedRandomAccessBucket;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.ManifestElement;
import freenet.support.io.DelayedFreeBucket;
import freenet.support.io.DelayedFreeRandomAccessBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.NullBucket;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;
import freenet.support.io.PersistentTempFileBucket;
import freenet.support.io.TempBucketFactory;

public class PersistentPutDir extends FCPMessage {

	static final String name = "PersistentPutDir";
	
	final String identifier;
	final FreenetURI uri;
	final FreenetURI privateURI;
	final int verbosity; 
	final short priorityClass;
	final Persistence persistence; 
	final boolean global;
	private final HashMap<String, Object> manifestElements;
	final String defaultName;
	final String token;
	final boolean started;
	final int maxRetries;
	final boolean wasDiskPut;
	private final SimpleFieldSet cached;
	final boolean dontCompress;
	final String compressorDescriptor;
	final boolean realTime;
	final byte[] splitfileCryptoKey;
	final InsertContext.CompatibilityMode compatMode;
	
	public PersistentPutDir(String identifier, FreenetURI publicURI, FreenetURI privateURI, int verbosity, short priorityClass,
	        Persistence persistence, boolean global, String defaultName, HashMap<String, Object> manifestElements,
	        String token, boolean started, int maxRetries, boolean dontCompress, String compressorDescriptor, boolean wasDiskPut, boolean realTime, byte[] splitfileCryptoKey,
	        InsertContext.CompatibilityMode cmode) {
		this.identifier = identifier;
		this.uri = publicURI;
		this.privateURI = privateURI;
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.persistence = persistence;
		this.global = global;
		this.defaultName = defaultName;
		this.manifestElements = manifestElements;
		this.token = token;
		this.started = started;
		this.maxRetries = maxRetries;
		this.wasDiskPut = wasDiskPut;
		this.dontCompress = dontCompress;
		this.compressorDescriptor = compressorDescriptor;
		this.realTime = realTime;
		this.splitfileCryptoKey = splitfileCryptoKey;
		this.compatMode = cmode;
		cached = generateFieldSet();
	}

	private SimpleFieldSet generateFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false); // false because this can get HUGE
		fs.putSingle("Identifier", identifier);
		fs.putSingle("URI", uri.toString(false, false));
		if(privateURI != null)
			fs.putSingle("PrivateURI", privateURI.toString(false, false));
		fs.put("Verbosity", verbosity);
		fs.putSingle("Persistence", persistence.toString().toLowerCase());
		fs.put("PriorityClass", priorityClass);
		fs.put("Global", global);
		fs.putSingle("PutDirType", wasDiskPut ? "disk" : "complex");
		fs.putOverwrite("CompatibilityMode", compatMode.name());
		SimpleFieldSet files = new SimpleFieldSet(false);
		// Flatten the hierarchy, it can be reconstructed on restarting.
		// Storing it directly would be a PITA.
		// FIXME/RESOLVE: The new BaseManifestPutter's container mode does not hold the origin data,
		//                 after composing the PutHandlers (done in BaseManifestPutter), they are 'lost':
		//                 A resumed half done container put can not get the complete file list from BaseManifestPutter.
		//                 Is it really necessary to include the file list here?
		ManifestElement[] elements = BaseManifestPutter.flatten(manifestElements);
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
				Bucket origData = e.getData();
				Bucket data = origData;
				if(data instanceof DelayedFreeBucket) {
					data = ((DelayedFreeBucket)data).getUnderlying();
				} else if(data instanceof DelayedFreeRandomAccessBucket) {
				    data = ((DelayedFreeRandomAccessBucket)data).getUnderlying();
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
				} else if (data instanceof PaddedEphemerallyEncryptedBucket || data instanceof NullBucket || data instanceof PersistentTempFileBucket || data instanceof TempBucketFactory.TempBucket || data instanceof EncryptedRandomAccessBucket) {
					subset.putSingle("UploadFrom", "direct");
				} else {
					throw new IllegalStateException("Don't know what to do with bucket: "+data);
				}
			}
			files.put(num, subset);
		}
		files.put("Count", elements.length);
		fs.put("Files", files);
		if(token != null)
			fs.putSingle("ClientToken", token);
		fs.put("Started", started);
		fs.put("MaxRetries", maxRetries);
		fs.put("DontCompress", dontCompress);
		if(compressorDescriptor != null)
			fs.putSingle("Codecs", compressorDescriptor);
		fs.put("RealTime", realTime);
		if(splitfileCryptoKey != null)
			fs.putSingle("SplitfileCryptoKey", HexUtil.bytesToHex(splitfileCryptoKey));
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

}
