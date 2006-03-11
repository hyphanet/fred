package freenet.node.fcp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.async.ManifestElement;
import freenet.client.async.SimpleManifestPutter;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.SimpleEventProducer;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.PaddedEphemerallyEncryptedBucket;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileBucket;

public class ClientPutDir extends ClientPutBase implements ClientEventListener, ClientCallback {

	private final HashMap manifestElements;
	private final SimpleManifestPutter putter;
	private final String defaultName;
	
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
		putter = p;
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this);
	}

	public ClientPutDir(SimpleFieldSet fs, FCPClient client) throws PersistenceParseException, IOException {
		super(fs, client);
		SimpleFieldSet files = fs.subset("Files");
		defaultName = fs.get("DefaultName");
		// Flattened for disk, sort out afterwards
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
			Bucket data;
			Logger.minor(this, "Parsing "+i);
			if(uploadFrom == null || uploadFrom.equalsIgnoreCase("direct")) {
				// Direct (persistent temp bucket)
				byte[] key = HexUtil.hexToBytes(subset.get("TempBucket.DecryptKey"));
				String fnam = subset.get("TempBucket.Filename");
				long sz = Long.parseLong(subset.get("TempBucket.Size"));
				data = client.server.node.persistentTempBucketFactory.registerEncryptedBucket(fnam, key, sz);
				if(data.size() != sz)
					throw new PersistenceParseException("Size of bucket is wrong: "+data.size()+" should be "+sz);
			} else {
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
			}
			ManifestElement me = new ManifestElement(name, data, contentTypeOverride);
			v.add(me);
		}
		manifestElements = SimpleManifestPutter.unflatten(v);
		SimpleManifestPutter p;
		try {
			p = new SimpleManifestPutter(this, client.node.chkPutScheduler, client.node.sskPutScheduler,
					manifestElements, priorityClass, uri, defaultName, ctx, getCHKOnly, client);
		} catch (InserterException e) {
			onFailure(e, null);
			p = null;
		}
		putter = p;
		if(!finished)
			start();
	}

	public void start() {
		try {
			if(putter != null)
				putter.start();
		} catch (InserterException e) {
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
			Bucket data = e.getData();
			SimpleFieldSet subset = new SimpleFieldSet(true);
			subset.put("Name", name);
			if(mimeOverride != null)
				subset.put("Metadata.ContentType", mimeOverride);
			// What to do with the bucket?
			// It is either a persistent encrypted bucket or a file bucket ...
			if(data instanceof FileBucket) {
				subset.put("UploadFrom", "disk");
				subset.put("Filename", ((FileBucket)data).getFile().getPath());
			} else if(data instanceof PaddedEphemerallyEncryptedBucket) {
				subset.put("UploadFrom", "direct");
				// the bucket is a persistent encrypted temp bucket
				PaddedEphemerallyEncryptedBucket bucket = (PaddedEphemerallyEncryptedBucket) data;
				subset.put("TempBucket.DecryptKey", HexUtil.bytesToHex(bucket.getKey()));
				subset.put("TempBucket.Filename", ((FileBucket)(bucket.getUnderlying())).getName());
				subset.put("TempBucket.Size", Long.toString(bucket.size()));
			} else {
				throw new IllegalStateException("Don't know what to do with bucket: "+data);
			}
			files.put(num, subset);
		}
		fs.put("Files", files);
		return fs;
	}

	protected FCPMessage persistentTagMessage() {
		throw new UnsupportedOperationException();
	}

	protected String getTypeName() {
		return "PUTDIR";
	}

}
