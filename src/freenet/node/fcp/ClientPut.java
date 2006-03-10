package freenet.node.fcp;

import java.io.File;
import java.io.IOException;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.client.async.ClientPutter;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.PaddedEphemerallyEncryptedBucket;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileBucket;

public class ClientPut extends ClientPutBase {

	final ClientPutter inserter;
	final InsertBlock block;
	/** Was this from disk? Purely for PersistentPut */
	private final boolean fromDisk;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) throws IdentifierCollisionException {
		super(message.uri, message.identifier, message.verbosity, handler, 
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries);
		this.fromDisk = message.fromDisk;
		this.origFilename = message.origFilename;
		// Now go through the fields one at a time
		String mimeType = message.contentType;
		clientToken = message.clientToken;
		block = new InsertBlock(message.bucket, new ClientMetadata(mimeType), uri);
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this);
		inserter = new ClientPutter(this, message.bucket, uri, new ClientMetadata(mimeType), 
				ctx, client.node.chkPutScheduler, client.node.sskPutScheduler, priorityClass, getCHKOnly, false, client);
		if(persistenceType != PERSIST_CONNECTION && handler != null)
			sendPendingMessages(handler.outputHandler, true);
	}
	
	/**
	 * Create from a persisted SimpleFieldSet.
	 * Not very tolerant of errors, as the input was generated
	 * by the node.
	 * @throws PersistenceParseException 
	 * @throws IOException 
	 */
	public ClientPut(SimpleFieldSet fs, FCPClient client2) throws PersistenceParseException, IOException {
		super(fs, client2);
		String mimeType = fs.get("Metadata.ContentType");
		fromDisk = Fields.stringToBool(fs.get("FromDisk"), false);
		Bucket data;
		if(fromDisk) {
			origFilename = new File(fs.get("Filename"));
			data = new FileBucket(origFilename, true, false, false, false);
		} else {
			origFilename = null;
			if(!succeeded) {
				byte[] key = HexUtil.hexToBytes(fs.get("TempBucket.DecryptKey"));
				String fnam = fs.get("TempBucket.Filename");
				long sz = Long.parseLong(fs.get("TempBucket.Size"));
				data = client.server.node.persistentTempBucketFactory.registerEncryptedBucket(fnam, key, sz);
				if(data.size() != sz)
					throw new PersistenceParseException("Size of bucket is wrong: "+data.size()+" should be "+sz);
			} else data = null;
		}
		block = new InsertBlock(data, new ClientMetadata(mimeType), uri);
		inserter = new ClientPutter(this, data, uri, new ClientMetadata(mimeType), ctx, client.node.chkPutScheduler, client.node.sskPutScheduler, priorityClass, getCHKOnly, false, client);
		if(!finished)
			start();
	}

	void start() {
		try {
			inserter.start();
		} catch (InserterException e) {
			onFailure(e, null);
		}
	}

	protected void freeData() {
		block.getData().free();
	}
	
	public synchronized SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.put("Metadata.ContentType", block.clientMetadata.getMIMEType());
		fs.put("GetCHKOnly", Boolean.toString(getCHKOnly));
		fs.put("FromDisk", Boolean.toString(fromDisk));
		if(fromDisk) {
			fs.put("Filename", origFilename.getPath());
		} else {
			// the bucket is a persistent encrypted temp bucket
			PaddedEphemerallyEncryptedBucket bucket = (PaddedEphemerallyEncryptedBucket) block.getData();
			fs.put("TempBucket.DecryptKey", HexUtil.bytesToHex(bucket.getKey()));
			fs.put("TempBucket.Filename", ((FileBucket)(bucket.getUnderlying())).getName());
			fs.put("TempBucket.Size", Long.toString(bucket.size()));
		}
		return fs;
	}

	protected freenet.client.async.ClientRequester getClientRequest() {
		return inserter;
	}

	protected FCPMessage persistentTagMessage() {
		return new PersistentPut(identifier, uri, verbosity, priorityClass, fromDisk, persistenceType, origFilename, 
				block.clientMetadata.getMIMEType(), client.isGlobalQueue);
	}

	protected String getTypeName() {
		return "PUT";
	}
	
}
