package freenet.node.fcp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.SimpleEventProducer;
import freenet.client.events.SplitfileProgressEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.PaddedEphemerallyEncryptedBucket;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileBucket;

public class ClientPut extends ClientRequest implements ClientCallback, ClientEventListener {

	final FreenetURI uri;
	final ClientPutter inserter;
	final InserterContext ctx;
	final InsertBlock block;
	/** Original FCP connection handler. Null if persistence != connection. */
	final FCPConnectionHandler origHandler;
	/** Client originating this request */
	final FCPClient client;
	final String identifier;
	final boolean getCHKOnly;
	short priorityClass;
	private final short persistenceType;
	final int verbosity;
	/** Has the request finished? */
	private boolean finished;
	/** Client token - opaque string returned to client in PersistentPut */
	private String clientToken;
	/** Was this from disk? Purely for PersistentPut */
	private final boolean fromDisk;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	
	// Verbosity bitmasks
	private int VERBOSITY_SPLITFILE_PROGRESS = 1;
	private int VERBOSITY_COMPRESSION_START_END = 512;
	
	// Stuff waiting for reconnection
	/** Has the request succeeded? */
	private boolean succeeded;
	/** If the request failed, how did it fail? PutFailedMessage is the most
	 * convenient way to store this (InserterException has a stack trace!).
	 */
	private PutFailedMessage putFailedMessage;
	/** URI generated for the insert. */
	private FreenetURI generatedURI;
	// This could be a SimpleProgress, or it could be started/finished compression.
	// Not that important, so not saved on persistence.
	// Probably saving it would conflict with later changes (full persistence at
	// ClientPutter level).
	private FCPMessage progressMessage;
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) throws IdentifierCollisionException {
		this.verbosity = message.verbosity;
		this.identifier = message.identifier;
		this.getCHKOnly = message.getCHKOnly;
		this.priorityClass = message.priorityClass;
		this.persistenceType = message.persistenceType;
		this.fromDisk = message.fromDisk;
		this.origFilename = message.origFilename;
		if(persistenceType == PERSIST_CONNECTION)
			this.origHandler = handler;
		else
			this.origHandler = null;
		if(message.global) {
			client = handler.server.globalClient;
		} else {
			client = handler.getClient();
		}
		ctx = new InserterContext(client.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = message.dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = message.maxRetries;
		// Now go through the fields one at a time
		uri = message.uri;
		String mimeType = message.contentType;
		clientToken = message.clientToken;
		block = new InsertBlock(message.bucket, new ClientMetadata(mimeType), uri);
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this);
		inserter = new ClientPutter(this, message.bucket, uri, new ClientMetadata(mimeType), ctx, client.node.chkPutScheduler, client.node.sskPutScheduler, priorityClass, getCHKOnly, false, client);
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
		uri = new FreenetURI(fs.get("URI"));
		identifier = fs.get("Identifier");
		verbosity = Integer.parseInt(fs.get("Verbosity"));
		priorityClass = Short.parseShort(fs.get("PriorityClass"));
		persistenceType = ClientRequest.parsePersistence(fs.get("Persistence"));
		if(persistenceType == ClientRequest.PERSIST_CONNECTION
				|| persistenceType == ClientRequest.PERSIST_REBOOT)
			throw new IllegalArgumentException("Reading in persistent ClientPut, but persistence type = "+ClientRequest.persistenceTypeString(persistenceType)+" so shouldn't have been saved in the first place");
		this.client = client2;
		origHandler = null;
		String mimeType = fs.get("Metadata.ContentType");
		getCHKOnly = Fields.stringToBool(fs.get("CHKOnly"), false);
		boolean dontCompress = Fields.stringToBool(fs.get("DontCompress"), false);
		int maxRetries = Integer.parseInt(fs.get("MaxRetries"));
		clientToken = fs.get("ClientToken");
		fromDisk = Fields.stringToBool(fs.get("FromDisk"), false);
		finished = Fields.stringToBool(fs.get("Finished"), false);
		//finished = false;
		succeeded = Fields.stringToBool(fs.get("Succeeded"), false);
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
		ctx = new InserterContext(client.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
		block = new InsertBlock(data, new ClientMetadata(mimeType), uri);
		String genURI = fs.get("GeneratedURI");
		if(genURI != null)
			generatedURI = new FreenetURI(genURI);
		if(finished && (!succeeded))
			putFailedMessage = new PutFailedMessage(fs.subset("PutFailed"), false);
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

	public void onLostConnection() {
		if(persistenceType == PERSIST_CONNECTION)
			cancel();
		// otherwise ignore
	}
	
	public void cancel() {
		inserter.cancel();
	}

	public void onSuccess(BaseClientPutter state) {
		synchronized(this) {
			progressMessage = null;
			succeeded = true;
			finished = true;
		}
		trySendFinalMessage(null);
		block.getData().free();
		finish();
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		synchronized(this) {
			finished = true;
			putFailedMessage = new PutFailedMessage(e, identifier);
		}
		trySendFinalMessage(null);
		block.getData().free();
		finish();
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		synchronized(this) {
			if(generatedURI != null && !uri.equals(generatedURI))
				Logger.error(this, "onGeneratedURI("+uri+","+state+") but already set generatedURI to "+generatedURI);
			generatedURI = uri;
		}
		trySendGeneratedURIMessage(null);
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		// ignore
	}

	public void onFailure(FetchException e, ClientGetter state) {
		// ignore
	}

	public void receive(ClientEvent ce) {
		if(finished) return;
		if(ce instanceof SplitfileProgressEvent) {
			if((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS) {
				SimpleProgressMessage progress = 
					new SimpleProgressMessage(identifier, (SplitfileProgressEvent)ce);
				trySendProgressMessage(progress, VERBOSITY_SPLITFILE_PROGRESS, null);
			}
		} else if(ce instanceof StartedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				StartedCompressionMessage msg =
					new StartedCompressionMessage(identifier, ((StartedCompressionEvent)ce).codec);
				trySendProgressMessage(msg, VERBOSITY_COMPRESSION_START_END, null);
			}
		} else if(ce instanceof FinishedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				FinishedCompressionMessage msg = 
					new FinishedCompressionMessage(identifier, (FinishedCompressionEvent)ce);
				trySendProgressMessage(msg, VERBOSITY_COMPRESSION_START_END, null);
			}
		}
	}
	
	private void trySendFinalMessage(FCPConnectionOutputHandler handler) {
		
		FCPMessage msg;
		
		if(succeeded) {
			msg = new PutSuccessfulMessage(identifier, generatedURI);
		} else {
			msg = putFailedMessage;
		}
		
		if(msg == null) {
			Logger.error(this, "Trying to send null message on "+this, new Exception("error"));
		} else {
			if(handler != null)
				handler.queue(msg);
			else
				client.queueClientRequestMessage(msg, 0);
		}
	}

	private void trySendGeneratedURIMessage(FCPConnectionOutputHandler handler) {
		FCPMessage msg = new URIGeneratedMessage(generatedURI, identifier);
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, 0);
	}

	private void trySendProgressMessage(FCPMessage msg, int verbosity, FCPConnectionOutputHandler handler) {
		if(persistenceType != PERSIST_CONNECTION)
			progressMessage = msg;
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, verbosity);
	}
	
	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest) {
		if(persistenceType == PERSIST_CONNECTION) {
			Logger.error(this, "WTF? persistenceType="+persistenceType, new Exception("error"));
			return;
		}
		if(includePersistentRequest) {
			FCPMessage msg = new PersistentPut(identifier, uri, verbosity, priorityClass, fromDisk, persistenceType, origFilename, block.clientMetadata.getMIMEType(), client.isGlobalQueue);
			handler.queue(msg);
		}
		if(generatedURI != null)
			trySendGeneratedURIMessage(handler);
		if(progressMessage != null)
			handler.queue(progressMessage);
		if(finished)
			trySendFinalMessage(handler);
	}
	
	/** Request completed. But we may have to stick around until we are acked. */
	private void finish() {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION)
			origHandler.finishedClientRequest(this);
		client.finishedClientRequest(this);
	}
	
	public boolean isPersistent() {
		return persistenceType != ClientRequest.PERSIST_CONNECTION;
	}

	public void dropped() {
		cancel();
		block.getData().free();
	}
	
	public String getIdentifier() {
		return identifier;
	}

	public void write(BufferedWriter w) throws IOException {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION) {
			Logger.error(this, "Not persisting as persistenceType="+persistenceType);
			return;
		}
		// Persist the request to disk
		SimpleFieldSet fs = getFieldSet();
		fs.writeTo(w);
	}
	
	public synchronized SimpleFieldSet getFieldSet() throws IOException {
		SimpleFieldSet fs = new SimpleFieldSet(true); // we will need multi-level later...
		fs.put("Type", "PUT");
		fs.put("URI", uri.toString(false));
		fs.put("Identifier", identifier);
		fs.put("Verbosity", Integer.toString(verbosity));
		fs.put("PriorityClass", Short.toString(priorityClass));
		fs.put("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		fs.put("ClientName", client.name);
		fs.put("Metadata.ContentType", block.clientMetadata.getMIMEType());
		fs.put("GetCHKOnly", Boolean.toString(getCHKOnly));
		// finished => persistence of completion state, pending messages
		//fs.put("Finished", Boolean.toString(finished));
		fs.put("ClientToken", clientToken);
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
		fs.put("DontCompress", Boolean.toString(ctx.dontCompress));
		fs.put("MaxRetries", Integer.toString(ctx.maxInsertRetries));
		fs.put("Finished", Boolean.toString(finished));
		fs.put("Succeeded", Boolean.toString(succeeded));
		if(generatedURI != null)
			fs.put("GeneratedURI", generatedURI.toString(false));
		if(finished && (!succeeded))
			// Should have a putFailedMessage... unless there is a race condition.
			fs.put("PutFailed", putFailedMessage.getFieldSet(false));
		fs.put("Global", Boolean.toString(client.isGlobalQueue));
		return fs;
	}

	public boolean hasFinished() {
		return finished;
	}

	public boolean isPersistentForever() {
		return persistenceType == ClientRequest.PERSIST_FOREVER;
	}

	public void setPriorityClass(short priorityClass) {
		this.priorityClass = priorityClass;
		inserter.setPriorityClass(priorityClass);
	}

	public void setClientToken(String clientToken) {
		this.clientToken = clientToken;
	}

}
