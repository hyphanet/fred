package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import freenet.client.InserterException;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientRequester;
import freenet.client.async.ManifestElement;
import freenet.client.async.SimpleManifestPutter;
import freenet.client.events.ClientEventListener;
import freenet.keys.FreenetURI;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;

public class ClientPutDir extends ClientPutBase implements ClientEventListener, ClientCallback {

	private final HashMap manifestElements;
	private final SimpleManifestPutter putter;
	private final String defaultName;
	private final long totalSize;
	private final int numberOfFiles;
	
	public ClientPutDir(FCPConnectionHandler handler, ClientPutDirMessage message, 
			HashMap manifestElements) throws IdentifierCollisionException {
		super(message.uri, message.identifier, message.verbosity, handler,
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries);
		this.manifestElements = manifestElements;
		this.defaultName = message.defaultName;
		SimpleManifestPutter p;
		try {
			p = new SimpleManifestPutter(this, client.node.chkPutScheduler, client.node.sskPutScheduler,
					manifestElements, priorityClass, uri, defaultName, ctx, message.getCHKOnly, client);
		} catch (InserterException e) {
			onFailure(e, null);
			p = null;
		}
		if(p != null) {
			numberOfFiles = p.countFiles();
			totalSize = p.totalSize();
		} else {
			numberOfFiles = -1;
			totalSize = -1;
		}
		putter = p;
		if(persistenceType != PERSIST_CONNECTION) {
			client.register(this, false);
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
			if(handler != null && (!handler.isGlobalSubscribed()))
				handler.outputHandler.queue(msg);
		}
	}

	public ClientPutDir(SimpleFieldSet fs, FCPClient client) throws PersistenceParseException, IOException {
		super(fs, client);
		SimpleFieldSet files = fs.subset("Files");
		defaultName = fs.get("DefaultName");
		// Flattened for disk, sort out afterwards
		int fileCount = 0;
		long size = 0;
		Vector v = new Vector();
		for(int i=0;;i++) {
			String num = Integer.toString(i);
			SimpleFieldSet subset = files.subset(num);
			if(subset == null) break;
			// Otherwise serialize
			String name = subset.get("Name");
			if(name == null)
				throw new PersistenceParseException("No Name on "+i);
			String contentTypeOverride = subset.get("Metadata.ContentType");
			String uploadFrom = subset.get("UploadFrom");
			Bucket data = null;
			Logger.minor(this, "Parsing "+i);
			Logger.minor(this, "UploadFrom="+uploadFrom);
			ManifestElement me;
			if((uploadFrom == null) || uploadFrom.equalsIgnoreCase("direct")) {
				long sz = Long.parseLong(subset.get("DataLength"));
				if(!finished) {
					// Direct (persistent temp bucket)
					byte[] key = HexUtil.hexToBytes(subset.get("TempBucket.DecryptKey"));
					String fnam = subset.get("TempBucket.Filename");
					data = client.server.node.persistentTempBucketFactory.registerEncryptedBucket(fnam, key, sz);
					if(data.size() != sz)
						throw new PersistenceParseException("Size of bucket is wrong: "+data.size()+" should be "+sz);
				} else {
					data = null;
				}
				me = new ManifestElement(name, data, contentTypeOverride, sz);
				fileCount++;
			} else if(uploadFrom.equalsIgnoreCase("disk")) {
				long sz = Long.parseLong(subset.get("DataLength"));
				// Disk
				String f = subset.get("Filename");
				if(f == null)
					throw new PersistenceParseException("UploadFrom=disk but no name on "+i);
				File ff = new File(f);
				if(!(ff.exists() && ff.canRead())) {
					Logger.error(this, "File no longer exists, cancelling upload: "+ff);
					throw new IOException("File no longer exists, cancelling upload: "+ff);
				}
				data = new FileBucket(ff, true, false, false, false);
				me = new ManifestElement(name, data, contentTypeOverride, sz);
				fileCount++;
			} else if(uploadFrom.equalsIgnoreCase("redirect")) {
				FreenetURI targetURI = new FreenetURI(subset.get("TargetURI"));
				me = new ManifestElement(name, targetURI, contentTypeOverride);
			} else
				throw new PersistenceParseException("Don't know UploadFrom="+uploadFrom);
			v.add(me);
			if((data != null) && (data.size() > 0))
				size += data.size();
		}
		manifestElements = SimpleManifestPutter.unflatten(v);
		SimpleManifestPutter p = null;
		try {
			if(!finished)
				p = new SimpleManifestPutter(this, client.node.chkPutScheduler, client.node.sskPutScheduler,
						manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly, client);
		} catch (InserterException e) {
			onFailure(e, null);
			p = null;
		}
		putter = p;
		numberOfFiles = fileCount;
		totalSize = size;
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
	}

	public void start() {
		if(finished) return;
		if(started) return;
		try {
			if(putter != null)
				putter.start();
			started = true;
			Logger.minor(this, "Started "+putter);
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage();
				client.queueClientRequestMessage(msg, 0);
			}
		} catch (InserterException e) {
			started = true;
			onFailure(e, null);
		}
	}
	
	public void onLostConnection() {
		if(persistenceType == PERSIST_CONNECTION)
			cancel();
		// otherwise ignore
	}
	
	protected void freeData() {
		freeData(manifestElements);
	}
	
	private void freeData(HashMap manifestElements) {
		Iterator i = manifestElements.values().iterator();
		while(i.hasNext()) {
			Object o = i.next();
			if(o instanceof HashMap)
				freeData((HashMap)o);
			else {
				ManifestElement e = (ManifestElement) o;
				e.freeData();
			}
		}
	}

	protected ClientRequester getClientRequest() {
		return putter;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		// Translate manifestElements directly into a fieldset
		SimpleFieldSet files = new SimpleFieldSet(true);
		// Flatten the hierarchy, it can be reconstructed on restarting.
		// Storing it directly would be a PITA.
		ManifestElement[] elements = SimpleManifestPutter.flatten(manifestElements);
		fs.put("DefaultName", defaultName);
		for(int i=0;i<elements.length;i++) {
			String num = Integer.toString(i);
			ManifestElement e = elements[i];
			String name = e.getName();
			String mimeOverride = e.getMimeTypeOverride();
			SimpleFieldSet subset = new SimpleFieldSet(true);
			subset.put("Name", name);
			if(mimeOverride != null)
				subset.put("Metadata.ContentType", mimeOverride);
			FreenetURI target = e.getTargetURI();
			if(target != null) {
				subset.put("UploadFrom", "redirect");
				subset.put("TargetURI", target.toString());
			} else {
				Bucket data = e.getData();
				// What to do with the bucket?
				// It is either a persistent encrypted bucket or a file bucket ...
				subset.put("DataLength", Long.toString(e.getSize()));
				if(data instanceof FileBucket) {
					subset.put("UploadFrom", "disk");
					subset.put("Filename", ((FileBucket)data).getFile().getPath());
				} else if(finished) {
					subset.put("UploadFrom", "direct");
				} else if(data instanceof PaddedEphemerallyEncryptedBucket) {
					subset.put("UploadFrom", "direct");
					// the bucket is a persistent encrypted temp bucket
					PaddedEphemerallyEncryptedBucket bucket = (PaddedEphemerallyEncryptedBucket) data;
					subset.put("TempBucket.DecryptKey", HexUtil.bytesToHex(bucket.getKey()));
					subset.put("TempBucket.Filename", ((FileBucket)(bucket.getUnderlying())).getName());
				} else {
					throw new IllegalStateException("Don't know what to do with bucket: "+data);
				}
			}
			files.put(num, subset);
		}
		fs.put("Files", files);
		return fs;
	}

	protected FCPMessage persistentTagMessage() {
		return new PersistentPutDir(identifier, uri, verbosity, priorityClass,
				persistenceType, global, defaultName, manifestElements, clientToken, started);
	}

	protected String getTypeName() {
		return "PUTDIR";
	}

	public boolean hasSucceeded() {
		return succeeded;
	}

	public FreenetURI getFinalURI() {
		return generatedURI;
	}

	public boolean isDirect() {
		// TODO Auto-generated method stub
		return false;
	}

	public int getNumberOfFiles() {
		return numberOfFiles;
	}

	public long getTotalDataSize() {
		return totalSize;
	}

}
