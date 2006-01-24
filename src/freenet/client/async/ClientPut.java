package freenet.client.async;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;

public class ClientPut extends ClientRequest implements PutCompletionCallback {

	final Client client;
	final Bucket data;
	final FreenetURI targetURI;
	final ClientMetadata cm;
	final InserterContext ctx;
	final ClientRequestScheduler scheduler;
	private ClientPutState currentState;
	private boolean finished;
	private final boolean getCHKOnly;

	public ClientPut(Client client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InserterContext ctx,
			ClientRequestScheduler scheduler, short priorityClass, boolean getCHKOnly) {
		super(priorityClass);
		this.cm = cm;
		this.getCHKOnly = getCHKOnly;
		this.client = client;
		this.data = data;
		this.targetURI = targetURI;
		this.ctx = ctx;
		this.scheduler = scheduler;
		this.finished = false;
		this.cancelled = false;
		try {
			start();
		} catch (InserterException e) {
			onFailure(e, null);
		}
	}

	private void start() throws InserterException {
		currentState =
			new SingleFileInserter(this, this, new InsertBlock(data, cm, targetURI), false, ctx, false, false, getCHKOnly);
	}

	void setCurrentState(ClientPutState s) {
		currentState = s;
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
		client.onGeneratedURI(key.getURI(), this);
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
	
}
