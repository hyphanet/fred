package freenet.node.fcp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;

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
import freenet.support.PaddedEphemerallyEncryptedBucket;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileBucket;

public class ClientPutDir extends ClientPutBase implements ClientEventListener, ClientCallback {

	private HashMap manifestElements;
	private SimpleManifestPutter putter;
	private InserterContext ctx;
	
	public ClientPutDir(FCPConnectionHandler handler, ClientPutDirMessage message, 
			HashMap manifestElements) throws IdentifierCollisionException {
		super(message.uri, message.identifier, message.verbosity, handler,
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries);
		this.manifestElements = manifestElements;
		ctx = new InserterContext(client.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = message.dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = message.maxRetries;
		try {
			putter = new SimpleManifestPutter(this, client.node.chkPutScheduler, client.node.sskPutScheduler,
					manifestElements, priorityClass, uri, message.defaultName, ctx, message.getCHKOnly, client);
		} catch (InserterException e) {
			onFailure(e, null);
		} 
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this);
	}

	public void start() {
		try {
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
