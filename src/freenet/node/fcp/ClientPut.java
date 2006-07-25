package freenet.node.fcp;

import java.io.File;
import java.io.IOException;

import freenet.client.ClientMetadata;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.io.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;

public class ClientPut extends ClientPutBase {

	final ClientPutter inserter;
	private final short uploadFrom;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	/** If uploadFrom==UPLOAD_FROM_REDIRECT, this is the target URI */
	private final FreenetURI targetURI;
	private final Bucket data;
	private final ClientMetadata clientMetadata;
	/** We store the size of inserted data before freeing it */
	private long finishedSize;
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) throws IdentifierCollisionException {
		super(message.uri, message.identifier, message.verbosity, handler, 
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries);
		this.uploadFrom = message.uploadFromType;
		this.origFilename = message.origFilename;
		// Now go through the fields one at a time
		String mimeType = message.contentType;
		clientToken = message.clientToken;
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, false);
		Bucket tempData = message.bucket;
		ClientMetadata cm = new ClientMetadata(mimeType);
		boolean isMetadata = false;
		Logger.minor(this, "data = "+tempData+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			this.targetURI = message.redirectTarget;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, targetURI, cm);
			cm = null;
			byte[] d;
			try {
				d = m.writeToByteArray();
			} catch (MetadataUnresolvedException e) {
				// Impossible
				Logger.error(this, "Impossible: "+e, e);
				onFailure(new InserterException(InserterException.INTERNAL_ERROR, "Impossible: "+e+" in ClientPut", null), null);
				this.data = null;
				clientMetadata = null;
				inserter = null;
				return;
			}
			tempData = new SimpleReadOnlyArrayBucket(d);
			isMetadata = true;
		} else
			targetURI = null;
		this.data = tempData;
		this.clientMetadata = cm;
		Logger.minor(this, "data = "+data+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		inserter = new ClientPutter(this, data, uri, cm, 
				ctx, client.node.chkPutScheduler, client.node.sskPutScheduler, priorityClass, 
				getCHKOnly, isMetadata, client, null);
		if((persistenceType != PERSIST_CONNECTION) && (handler != null))
			sendPendingMessages(handler.outputHandler, true, false, false);
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

		String from = fs.get("UploadFrom");
		
		if(from.equals("direct")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_DIRECT;
		} else if(from.equals("disk")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_DISK;
		} else if(from.equals("redirect")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_REDIRECT;
		} else {
			// FIXME remove this back compatibility hack in a few builds' time
			String s = fs.get("FromDisk");
			if(s.equalsIgnoreCase("true"))
				uploadFrom = ClientPutMessage.UPLOAD_FROM_DISK;
			else if(s.equalsIgnoreCase("false"))
				uploadFrom = ClientPutMessage.UPLOAD_FROM_DIRECT;
			else
				throw new PersistenceParseException("Unknown UploadFrom: "+from);
		}
		
		ClientMetadata cm = new ClientMetadata(mimeType);
		
		boolean isMetadata = false;
		
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DISK) {
			origFilename = new File(fs.get("Filename"));
			data = new FileBucket(origFilename, true, false, false, false);
			targetURI = null;
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT) {
			origFilename = null;
			if(!succeeded) {
				byte[] key = HexUtil.hexToBytes(fs.get("TempBucket.DecryptKey"));
				String fnam = fs.get("TempBucket.Filename");
				long sz = Long.parseLong(fs.get("TempBucket.Size"));
				try {
					data = client.server.node.persistentTempBucketFactory.registerEncryptedBucket(fnam, key, sz);
					if(data.size() != sz)
						throw new PersistenceParseException("Size of bucket is wrong: "+data.size()+" should be "+sz);
				} catch (IOException e) {
					throw new PersistenceParseException("Could not read old bucket (should be "+sz+" bytes) for "+identifier);
				}
			} else data = null;
			targetURI = null;
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			String target = fs.get("TargetURI");
			targetURI = new FreenetURI(target);
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, targetURI, cm);
			cm = null;
			byte[] d;
			try {
				d = m.writeToByteArray();
			} catch (MetadataUnresolvedException e) {
				// Impossible
				Logger.error(this, "Impossible: "+e, e);
				onFailure(new InserterException(InserterException.INTERNAL_ERROR, "Impossible: "+e+" in ClientPut", null), null);
				this.data = null;
				clientMetadata = null;
				origFilename = null;
				inserter = null;
				return;
			}
			data = new SimpleReadOnlyArrayBucket(d);
			origFilename = null;
			isMetadata = true;
		} else {
			throw new PersistenceParseException("shouldn't happen");
		}
		this.clientMetadata = cm;
		inserter = new ClientPutter(this, data, uri, cm, ctx, client.node.chkPutScheduler, 
				client.node.sskPutScheduler, priorityClass, getCHKOnly, isMetadata, client, fs.subset("progress"));
	}

	public void start() {
		if(finished) return;
		try {
			inserter.start();
		} catch (InserterException e) {
			started = true;
			onFailure(e, null);
		}
		started = true;
	}

	protected void freeData() {
		finishedSize=data.size();
		data.free();
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		// This is all fixed, so no need for synchronization.
		fs.put("Metadata.ContentType", clientMetadata.getMIMEType());
		fs.put("UploadFrom", ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DISK) {
			fs.put("Filename", origFilename.getPath());
		}  else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT) {
			// the bucket is a persistent encrypted temp bucket
			PaddedEphemerallyEncryptedBucket bucket = (PaddedEphemerallyEncryptedBucket) data;
			fs.put("TempBucket.DecryptKey", HexUtil.bytesToHex(bucket.getKey()));
			fs.put("TempBucket.Filename", ((FileBucket)(bucket.getUnderlying())).getName());
			fs.put("TempBucket.Size", Long.toString(bucket.size()));
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			fs.put("TargetURI", targetURI.toString());
		}
		if(inserter != null)  {
			SimpleFieldSet sfs = inserter.getProgressFieldset();
			fs.put("progress", sfs);
		}
		return fs;
	}

	protected freenet.client.async.ClientRequester getClientRequest() {
		return inserter;
	}

	protected FCPMessage persistentTagMessage() {
		return new PersistentPut(identifier, uri, verbosity, priorityClass, uploadFrom, targetURI, 
				persistenceType, origFilename, clientMetadata.getMIMEType(), client.isGlobalQueue,
				getDataSize(), clientToken, started);
	}

	protected String getTypeName() {
		return "PUT";
	}

	public boolean hasSucceeded() {
		return succeeded;
	}

	public FreenetURI getFinalURI() {
		return generatedURI;
	}

	public boolean isDirect() {
		return uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT;
	}

	public File getOrigFilename() {
		if(uploadFrom != ClientPutMessage.UPLOAD_FROM_DISK)
			return null;
		return origFilename;
	}

	public long getDataSize() {
		if(data == null)
			return finishedSize;
		else
			return data.size();
	}

	public String getMIMEType() {
		return clientMetadata.getMIMEType();
	}

}
