/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.api.Bucket;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.FileBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

/**
 * 
 * TODO: move hash stuffs into ClientPutBase ... and enforce hash verification at a lower level
 */
public class ClientPut extends ClientPutBase {

	final ClientPutter putter;
	private final short uploadFrom;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	/** If uploadFrom==UPLOAD_FROM_REDIRECT, this is the target URI */
	private final FreenetURI targetURI;
	private final Bucket data;
	private final ClientMetadata clientMetadata;
	/** We store the size of inserted data before freeing it */
	private long finishedSize;
	/** Filename if the file has one */
	private final String targetFilename;
	private boolean logMINOR;
	
	// FIXME: should be in ClientPutBase ... but we don't want to break insert resuming, do we ?
	protected final String salt;
	protected final byte[] saltedHash;
	
	/**
	 * Creates a new persistent insert.
	 * 
	 * @param uri
	 *            The URI to insert data to
	 * @param identifier
	 *            The identifier of the insert
	 * @param verbosity
	 *            The verbosity bitmask
	 * @param handler
	 *            The FCP connection handler
	 * @param priorityClass
	 *            The priority for this insert
	 * @param persistenceType
	 *            The persistence type of this insert
	 * @param clientToken
	 *            The client token of this insert
	 * @param global
	 *            Whether this insert appears on the global queue
	 * @param getCHKOnly
	 *            Whether only the resulting CHK is requested
	 * @param dontCompress
	 *            Whether the file should not be compressed
	 * @param maxRetries
	 *            The maximum number of retries
	 * @param uploadFromType
	 *            Where the file is uploaded from
	 * @param origFilename
	 *            The original filename
	 * @param contentType
	 *            The content type of the data
	 * @param data
	 *            The data (may be <code>null</code> if
	 *            <code>uploadFromType</code> is UPLOAD_FROM_DISK or
	 *            UPLOAD_FROM_REDIRECT)
	 * @param redirectTarget
	 *            The URI to redirect to (if <code>uploadFromType</code> is
	 *            UPLOAD_FROM_REDIRECT)
	 * @throws IdentifierCollisionException
	 * @throws NotAllowedException 
	 * @throws FileNotFoundException 
	 */
	public ClientPut(FCPClient globalClient, FreenetURI uri, String identifier, int verbosity, 
			short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly,
			boolean dontCompress, int maxRetries, short uploadFromType, File origFilename, String contentType,
			Bucket data, FreenetURI redirectTarget, String targetFilename, boolean earlyEncode) throws IdentifierCollisionException, NotAllowedException, FileNotFoundException {
		super(uri, identifier, verbosity, null, globalClient, priorityClass, persistenceType, null, true, getCHKOnly, dontCompress, maxRetries, earlyEncode);
		if(uploadFromType == ClientPutMessage.UPLOAD_FROM_DISK) {
			if(!globalClient.core.allowUploadFrom(origFilename))
				throw new NotAllowedException();
			if(!(origFilename.exists() && origFilename.canRead()))
				throw new FileNotFoundException();
		}

		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.targetFilename = targetFilename;
		this.uploadFrom = uploadFromType;
		this.origFilename = origFilename;
		// Now go through the fields one at a time
		String mimeType = contentType;
		this.clientToken = clientToken;
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, false);
		Bucket tempData = data;
		ClientMetadata cm = new ClientMetadata(mimeType);
		boolean isMetadata = false;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "data = "+tempData+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			this.targetURI = redirectTarget;
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
				putter = null;
				this.salt = null;
				this.saltedHash = null;
				return;
			}
			tempData = new SimpleReadOnlyArrayBucket(d);
			isMetadata = true;
		} else
			targetURI = null;

		this.data = tempData;
		this.clientMetadata = cm;
		this.salt = globalClient.name;
		this.saltedHash = comptuteHash(salt, data);

		if(logMINOR) Logger.minor(this, "data = "+data+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		putter = new ClientPutter(this, data, uri, cm, 
				ctx, client.core.requestStarters.chkPutScheduler, client.core.requestStarters.sskPutScheduler, priorityClass, 
				getCHKOnly, isMetadata, client.lowLevelClient, null, targetFilename);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
	}
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) throws IdentifierCollisionException, MessageInvalidException {
		super(message.uri, message.identifier, message.verbosity, handler, 
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries, message.earlyEncode);
		if((message.fileHash != null) && (message.fileHash.length() > 0)) {
			try {
				this.salt = handler.getConnectionIdentifier() + message.clientToken;
				this.saltedHash = Base64.decode(message.fileHash);
			} catch (IllegalBase64Exception e) {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Can't base64 decode " + ClientPutBase.FILE_HASH, identifier, global);
			}
		} else {
			this.salt = null;
			this.saltedHash = null;
		}
		
		if(message.uploadFromType == ClientPutMessage.UPLOAD_FROM_DISK) {
			if(!handler.server.core.allowUploadFrom(message.origFilename))
				throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Not allowed to upload from "+message.origFilename, identifier, global);
			else if(!handler.allowDDAFrom(message.origFilename, false))
				throw new MessageInvalidException(ProtocolErrorMessage.DIRECT_DISK_ACCESS_DENIED, "Not allowed to upload from "+message.origFilename+". Have you done a testDDA previously ?", identifier, global); 
		}
			
		this.targetFilename = message.targetFilename;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.uploadFrom = message.uploadFromType;
		this.origFilename = message.origFilename;
		// Now go through the fields one at a time
		String mimeType = message.contentType;
		if(mimeType == null && origFilename != null) {
			mimeType = DefaultMIMETypes.guessMIMEType(origFilename.getName(), true);
		}
		if(mimeType == null) {
			mimeType = DefaultMIMETypes.guessMIMEType(identifier, true);
		}
		clientToken = message.clientToken;
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, false);
		Bucket tempData = message.bucket;
		ClientMetadata cm = new ClientMetadata(mimeType);
		boolean isMetadata = false;
		if(logMINOR) Logger.minor(this, "data = "+tempData+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
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
				putter = null;
				return;
			}
			tempData = new SimpleReadOnlyArrayBucket(d);
			isMetadata = true;
		} else
			targetURI = null;
		this.data = tempData;
		this.clientMetadata = cm;
		
		// Check the hash : allow it to be null for backward compatibility
		if((salt != null) && !isHashVerified())
			throw new MessageInvalidException(ProtocolErrorMessage.DIRECT_DISK_ACCESS_DENIED, "The hash doesn't match!", identifier, global);
		
		if(logMINOR) Logger.minor(this, "data = "+data+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		putter = new ClientPutter(this, data, uri, cm, 
				ctx, client.core.requestStarters.chkPutScheduler, client.core.requestStarters.sskPutScheduler, priorityClass, 
				getCHKOnly, isMetadata, client.lowLevelClient, null, targetFilename);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
			if(handler != null && (!handler.isGlobalSubscribed()))
				handler.outputHandler.queue(msg);
		}
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
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
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
		
		targetFilename = fs.get("TargetFilename");
		this.salt = fs.get(ClientPutBase.SALT);
		String hash = fs.get(ClientPutBase.FILE_HASH);
		if(hash != null) {
			String mySaltedHash = null;
			try {
				mySaltedHash = new String(Base64.decode(hash));
			} catch (IllegalBase64Exception e) {
				throw new PersistenceParseException("Could not read FileHash for "+identifier+" : "+e, e);
			}
			this.saltedHash = mySaltedHash.getBytes("UTF-8");
		} else
			this.saltedHash = null;
		
		
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DISK) {
			origFilename = new File(fs.get("Filename"));
			if(logMINOR)
				Logger.minor(this, "Uploading from disk: "+origFilename+" for "+this);
			data = new FileBucket(origFilename, true, false, false, false, false);
			targetURI = null;
			
			if(salt != null) {
				if(!isHashVerified())
					throw new PersistenceParseException("The hash doesn't match! or an error has occured.");
			}
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT) {
			origFilename = null;
			if(logMINOR)
				Logger.minor(this, "Uploading from direct for "+this);
			if(!finished) {
				try {
					data = SerializableToFieldSetBucketUtil.create(fs.subset("TempBucket"), ctx.random, client.server.core.persistentTempBucketFactory);
				} catch (CannotCreateFromFieldSetException e) {
					throw new PersistenceParseException("Could not read old bucket for "+identifier+" : "+e, e);
				}
			} else {
				if(Logger.shouldLog(Logger.MINOR, this)) 
					Logger.minor(this, "Finished already so not reading bucket for "+this);
				data = null;
			}
			targetURI = null;
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			String target = fs.get("TargetURI");
			targetURI = new FreenetURI(target);
			if(logMINOR)
				Logger.minor(this, "Uploading from redirect for "+this+" : "+targetURI);
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
				putter = null;
				return;
			}
			data = new SimpleReadOnlyArrayBucket(d);
			origFilename = null;
			isMetadata = true;
		} else {
			throw new PersistenceParseException("shouldn't happen");
		}
		if(logMINOR) Logger.minor(this, "data = "+data);
		this.clientMetadata = cm;
		putter = new ClientPutter(this, data, uri, cm, ctx, client.core.requestStarters.chkPutScheduler, 
				client.core.requestStarters.sskPutScheduler, priorityClass, getCHKOnly, isMetadata, client.lowLevelClient, fs.subset("progress"), targetFilename);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
		
	}

	public void start() {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting "+this+" : "+identifier);
		synchronized(this) {
			if(finished) return;
		}
		try {
			putter.start(earlyEncode);
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage();
				client.queueClientRequestMessage(msg, 0);
			}
			synchronized(this) {
				started = true;
			}
		} catch (InserterException e) {
			synchronized(this) {
				started = true;
			}
			onFailure(e, null);
		} catch (Throwable t) {
			synchronized(this) {
				started = true;
			}
			onFailure(new InserterException(InserterException.INTERNAL_ERROR, t, null), null);
		}
	}

	protected void freeData() {
		if(data == null) return;
		finishedSize=data.size();
		data.free();
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		// This is all fixed, so no need for synchronization.
		fs.putSingle("Metadata.ContentType", clientMetadata.getMIMEType());
		fs.putSingle("UploadFrom", ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DISK) {
			fs.putSingle("Filename", origFilename.getPath());
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT) {
			if(!finished) {
				// the bucket is a persistent encrypted temp bucket
				bucketToFS(fs, "TempBucket", true, data);
			}
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			fs.putSingle("TargetURI", targetURI.toString());
		}
		if(putter != null)  {
			SimpleFieldSet sfs = putter.getProgressFieldset();
			fs.put("progress", sfs);
		}
		if(targetFilename != null)
			fs.putSingle("TargetFilename", targetFilename);
		fs.putSingle("EarlyEncode", Boolean.toString(earlyEncode));
		
		if(salt != null) {
			fs.putSingle(ClientPutBase.SALT, salt);
			fs.putSingle(ClientPutBase.FILE_HASH, Base64.encode(saltedHash));
		}
		
		return fs;
	}

	protected freenet.client.async.ClientRequester getClientRequest() {
		return putter;
	}

	protected FCPMessage persistentTagMessage() {
		return new PersistentPut(identifier, uri, verbosity, priorityClass, uploadFrom, targetURI, 
				persistenceType, origFilename, clientMetadata.getMIMEType(), client.isGlobalQueue,
				getDataSize(), clientToken, started, ctx.maxInsertRetries, targetFilename);
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

	public boolean canRestart() {
		if(!finished) {
			Logger.minor(this, "Cannot restart because not finished for "+identifier);
			return false;
		}
		if(succeeded) {
			Logger.minor(this, "Cannot restart because succeeded for "+identifier);
			return false;
		}
		return putter.canRestart();
	}

	public boolean restart() {
		if(!canRestart()) return false;
		setVarsRestart();
		try {
			if(putter.restart(earlyEncode)) {
				synchronized(this) {
					generatedURI = null;
					started = true;
				}
			}
			return true;
		} catch (InserterException e) {
			onFailure(e, null);
			return false;
		}
	}

	public void onFailure(FetchException e, ClientGetter state) {}

	public void onSuccess(FetchResult result, ClientGetter state) {}
	
	private boolean isHashVerified() {
		if (logMINOR) Logger.minor(this, "Found a hash : let's verify it");
		return saltedHash.equals(comptuteHash(salt, data)); 
	}
	
	private byte[] comptuteHash(String mySalt, Bucket content) {
		MessageDigest md = SHA256.getMessageDigest();
		byte[] foundHash = null;
		
		try {
			md.reset();
			md.update(mySalt.getBytes("UTF-8"));
			BufferedInputStream bis = new BufferedInputStream(data.getInputStream());
			byte[] buf = new byte[4096];
			while(bis.read(buf) > 0)
				md.update(buf);
			foundHash = md.digest();
		} catch (IOException e) {
			return null;
		} finally {
			SHA256.returnMessageDigest(md);	
		}
		
		return foundHash;
	}
}
