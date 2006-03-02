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
import freenet.support.Logger;
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
	final short priorityClass;
	private final short persistenceType;
	final int verbosity;
	/** Has the request finished? */
	private boolean finished;
	/** Client token - opaque string returned to client in PersistentPut */
	private final String clientToken;
	/** Was this from disk? Purely for PersistentPut */
	private final boolean fromDisk;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	
	// Verbosity bitmasks
	private int VERBOSITY_SPLITFILE_PROGRESS = 1;
	private int VERBOSITY_COMPRESSION_START_END = 512;
	
	// Stuff waiting for reconnection
	private FCPMessage finalMessage;
	private FCPMessage generatedURIMessage;
	// This could be a SimpleProgress, or it could be started/finished compression
	private FCPMessage progressMessage;
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) {
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
		client = handler.getClient();
		ctx = new InserterContext(client.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = message.dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = message.maxRetries;
		// Now go through the fields one at a time
		uri = message.uri;
		String mimeType = message.contentType;
		clientToken = message.clientToken;
		block = new InsertBlock(message.bucket, new ClientMetadata(mimeType), uri);
		inserter = new ClientPutter(this, message.bucket, uri, new ClientMetadata(mimeType), ctx, client.node.putScheduler, priorityClass, getCHKOnly, false, client);
	}
	
	/**
	 * Create from a persisted SimpleFieldSet.
	 * Not very tolerant of errors, as the input was generated
	 * by the node.
	 * @throws MalformedURLException 
	 */
	public ClientPut(SimpleFieldSet fs, FCPClient client2) throws MalformedURLException {
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
		getCHKOnly = Boolean.parseBoolean(fs.get("GetCHKOnly"));
		boolean dontCompress = Boolean.parseBoolean(fs.get("DontCompress"));
		int maxRetries = Integer.parseInt(fs.get("MaxRetries"));
		clientToken = fs.get("ClientToken");
		fromDisk = true;
		origFilename = new File(fs.get("Filename"));
		Bucket data = new FileBucket(origFilename, true, false, false);
		ctx = new InserterContext(client.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
		block = new InsertBlock(data, new ClientMetadata(mimeType), uri);
		inserter = new ClientPutter(this, data, uri, new ClientMetadata(mimeType), ctx, client.node.putScheduler, priorityClass, getCHKOnly, false, client);
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
		progressMessage = null;
		finished = true;
		FCPMessage msg = new PutSuccessfulMessage(identifier, state.getURI());
		trySendFinalMessage(msg);
		block.getData().free();
		finish();
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		finished = true;
		FCPMessage msg = new PutFailedMessage(e, identifier);
		trySendFinalMessage(msg);
		block.getData().free();
		finish();
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		trySendGeneratedURIMessage(new URIGeneratedMessage(uri, identifier));
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
				trySendProgressMessage(progress);
			}
		} else if(ce instanceof StartedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				StartedCompressionMessage msg =
					new StartedCompressionMessage(identifier, ((StartedCompressionEvent)ce).codec);
				trySendProgressMessage(msg);
			}
		} else if(ce instanceof FinishedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				FinishedCompressionMessage msg = 
					new FinishedCompressionMessage(identifier, (FinishedCompressionEvent)ce);
				trySendProgressMessage(msg);
			}
		}
	}
	
	private void trySendFinalMessage(FCPMessage msg) {
		if(persistenceType != PERSIST_CONNECTION)
			finalMessage = msg;
		FCPConnectionHandler conn = client.getConnection();
		if(conn != null)
			conn.outputHandler.queue(msg);
	}

	private void trySendGeneratedURIMessage(URIGeneratedMessage msg) {
		if(persistenceType != PERSIST_CONNECTION)
			generatedURIMessage = msg;
		FCPConnectionHandler conn = client.getConnection();
		if(conn != null)
			conn.outputHandler.queue(msg);
	}

	private void trySendProgressMessage(FCPMessage msg) {
		if(persistenceType != PERSIST_CONNECTION)
			progressMessage = msg;
		FCPConnectionHandler conn = client.getConnection();
		if(conn != null)
			conn.outputHandler.queue(msg);
	}
	
	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest) {
		if(persistenceType == PERSIST_CONNECTION) {
			Logger.error(this, "WTF? persistenceType="+persistenceType, new Exception("error"));
			return;
		}
		if(includePersistentRequest) {
			FCPMessage msg = new PersistentPut(identifier, uri, verbosity, priorityClass, fromDisk, persistenceType, origFilename, block.clientMetadata.getMIMEType());
			handler.queue(msg);
		}
		if(generatedURIMessage != null)
			handler.queue(generatedURIMessage);
		if(progressMessage != null)
			handler.queue(progressMessage);
		if(finalMessage != null)
			handler.queue(finalMessage);
	}
	
	/** Request completed. But we may have to stick around until we are acked. */
	private void finish() {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION) {
			origHandler.finishedClientRequest(this);
		} else {
			client.finishedClientRequest(this);
		}
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
		if(persistenceType != ClientRequest.PERSIST_REBOOT) {
			Logger.error(this, "Not persisting as persistenceType="+persistenceType);
		}
		// Persist the request to disk
		SimpleFieldSet fs = getFieldSet();
		fs.writeTo(w);
	}
	
	public SimpleFieldSet getFieldSet() throws IOException {
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
		if(!fromDisk) throw new UnsupportedOperationException("Persistent insert not from disk - NOT SUPPORTED");
		fs.put("Filename", origFilename.getPath());
		fs.put("DontCompress", Boolean.toString(ctx.dontCompress));
		fs.put("MaxRetries", Integer.toString(ctx.maxInsertRetries));
		return fs;
	}

	public boolean hasFinished() {
		return finished;
	}

	public boolean isPersistentForever() {
		return persistenceType == ClientRequest.PERSIST_FOREVER;
	}

}
