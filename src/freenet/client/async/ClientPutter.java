package freenet.client.async;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Logger;

public class ClientPutter extends BaseClientPutter implements PutCompletionCallback {

	final ClientCallback client;
	final Bucket data;
	final FreenetURI targetURI;
	final ClientMetadata cm;
	final InserterContext ctx;
	private ClientPutState currentState;
	private boolean finished;
	private final boolean getCHKOnly;
	private final boolean isMetadata;
	private FreenetURI uri;

	/**
	 * @param client The object to call back when we complete, or don't.
	 * @param data
	 * @param targetURI
	 * @param cm
	 * @param ctx
	 * @param scheduler
	 * @param priorityClass
	 * @param getCHKOnly
	 * @param isMetadata
	 * @param clientContext The client object for purposs of round-robin client balancing.
	 */
	public ClientPutter(ClientCallback client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InserterContext ctx,
			ClientRequestScheduler scheduler, short priorityClass, boolean getCHKOnly, boolean isMetadata, Object clientContext) {
		super(priorityClass, scheduler, clientContext);
		this.cm = cm;
		this.isMetadata = isMetadata;
		this.getCHKOnly = getCHKOnly;
		this.client = client;
		this.data = data;
		this.targetURI = targetURI;
		this.ctx = ctx;
		this.finished = false;
		this.cancelled = false;
	}

	public void start() throws InserterException {
		try {
			currentState =
				new SingleFileInserter(this, this, new InsertBlock(data, cm, targetURI), isMetadata, ctx, false, getCHKOnly, false);
			((SingleFileInserter)currentState).start();
		} catch (InserterException e) {
			finished = true;
			currentState = null;
		}
	}

	public void onSuccess(ClientPutState state) {
		finished = true;
		currentState = null;
		client.onSuccess(this);
	}

	public void onFailure(InserterException e, ClientPutState state) {
		finished = true;
		currentState = null;
		client.onFailure(e, this);
	}

	public void onEncode(ClientKey key, ClientPutState state) {
		this.uri = key.getURI();
		client.onGeneratedURI(uri, this);
	}
	
	public void cancel() {
		synchronized(this) {
			super.cancel();
			if(currentState != null)
				currentState.cancel();
		}
	}
	
	public boolean isFinished() {
		return finished || cancelled;
	}

	public FreenetURI getURI() {
		return uri;
	}

	public synchronized void onTransition(ClientPutState oldState, ClientPutState newState) {
		if(currentState == oldState)
			currentState = newState;
		else
			Logger.error(this, "onTransition: cur="+currentState+", old="+oldState+", new="+newState);
	}

	public void onMetadata(Metadata m, ClientPutState state) {
		Logger.error(this, "Got metadata on "+this+" from "+state+" (this means the metadata won't be inserted)");
	}
	
	public void notifyClients() {
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized));
	}
	
	public void onBlockSetFinished(ClientPutState state) {
		Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized();
	}
	
}
