package freenet.client.async;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
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
	private boolean cancelled;

	public ClientPut(Client client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InserterContext ctx,
			ClientRequestScheduler scheduler, short priorityClass) {
		super(priorityClass);
		this.cm = cm;
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
			new SingleFileInserter(this, this, new InsertBlock(data, cm, targetURI), false, ctx, false, false);
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
}
