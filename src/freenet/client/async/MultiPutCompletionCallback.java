package freenet.client.async;

import java.util.LinkedList;

import freenet.client.InserterException;

public class MultiPutCompletionCallback implements PutCompletionCallback, ClientPutState {

	private final LinkedList waitingFor;
	private final PutCompletionCallback cb;
	private final ClientPut parent;
	private boolean finished;
	private boolean started;
	
	public MultiPutCompletionCallback(PutCompletionCallback cb, ClientPut parent, boolean dontTellParent) {
		this.cb = cb;
		this.waitingFor = new LinkedList();
		this.parent = parent;
		finished = false;
		if(!dontTellParent)
			parent.setCurrentState(this);
	}

	public synchronized void onSuccess(ClientPutState state) {
		if(finished) return;
		waitingFor.remove(state);
		if(waitingFor.isEmpty() && started) {
			complete(null);
		}
	}

	public synchronized void onFailure(InserterException e, ClientPutState state) {
		if(finished) return;
		waitingFor.remove(state);
		if(waitingFor.isEmpty()) {
			complete(e);
		}
	}

	private synchronized void complete(InserterException e) {
		finished = true;
		if(e != null)
			cb.onFailure(e, this);
		else
			cb.onSuccess(this);
	}

	public synchronized void add(ClientPutState ps) {
		if(finished) return;
		waitingFor.add(ps);
	}

	public synchronized void arm() {
		started = true;
	}

	public ClientPut getParent() {
		return parent;
	}

}
