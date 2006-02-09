package freenet.node.fcp;

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
import freenet.client.events.SimpleEventProducer;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;

public class ClientPut extends ClientRequest implements ClientCallback, ClientEventListener {

	final FreenetURI uri;
	final ClientPutter inserter;
	final InserterContext ctx;
	final InsertBlock block;
	final FCPConnectionHandler handler;
	final String identifier;
	final boolean getCHKOnly;
	final short priorityClass;
	final int verbosity;
	private boolean finished;
	
	// Verbosity bitmasks
	private int VERBOSITY_SPLITFILE_PROGRESS = 1;
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) {
		this.verbosity = message.verbosity;
		this.handler = handler;
		this.identifier = message.identifier;
		this.getCHKOnly = message.getCHKOnly;
		this.priorityClass = message.priorityClass;
		ctx = new InserterContext(handler.defaultInsertContext, new SimpleEventProducer());
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = message.maxRetries;
		// Now go through the fields one at a time
		uri = message.uri;
		String mimeType = message.contentType;
		block = new InsertBlock(message.bucket, new ClientMetadata(mimeType), uri);
		inserter = new ClientPutter(this, message.bucket, uri, new ClientMetadata(mimeType), ctx, handler.node.putScheduler, priorityClass, getCHKOnly, false, handler.defaultInsertContext);
	}

	void start() {
		try {
			inserter.start();
		} catch (InserterException e) {
			onFailure(e, null);
		}
	}

	public void cancel() {
		inserter.cancel();
	}

	public void onSuccess(BaseClientPutter state) {
		finished = true;
		FCPMessage msg = new PutSuccessfulMessage(identifier, state.getURI());
		handler.outputHandler.queue(msg);
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		finished = true;
		FCPMessage msg = new PutFailedMessage(e, identifier);
		handler.outputHandler.queue(msg);
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		FCPMessage msg = new URIGeneratedMessage(uri, identifier);
		handler.outputHandler.queue(msg);
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		// ignore
	}

	public void onFailure(FetchException e, ClientGetter state) {
		// ignore
	}

	public void receive(ClientEvent ce) {
		if(finished) return;
		if(!(((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS) &&
				(ce instanceof SplitfileProgressEvent)))
			return;
		SimpleProgressMessage progress = 
			new SimpleProgressMessage(identifier, (SplitfileProgressEvent)ce);
		handler.outputHandler.queue(progress);
	}

}
