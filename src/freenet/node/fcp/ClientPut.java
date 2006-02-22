package freenet.node.fcp;

import java.io.File;

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
import freenet.support.Logger;

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
		ctx = new InserterContext(handler.defaultInsertContext, new SimpleEventProducer());
		if(message.dontCompress)
			ctx.dontCompress = true;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = message.maxRetries;
		// Now go through the fields one at a time
		uri = message.uri;
		String mimeType = message.contentType;
		clientToken = message.clientToken;
		block = new InsertBlock(message.bucket, new ClientMetadata(mimeType), uri);
		inserter = new ClientPutter(this, message.bucket, uri, new ClientMetadata(mimeType), ctx, handler.node.putScheduler, priorityClass, getCHKOnly, false, handler);
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

}
