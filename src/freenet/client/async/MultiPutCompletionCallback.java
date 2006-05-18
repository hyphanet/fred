package freenet.client.async;

import java.util.LinkedList;
import java.util.ListIterator;

import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.support.Logger;

public class MultiPutCompletionCallback implements PutCompletionCallback, ClientPutState {

	// LinkedList's rather than HashSet's for memory reasons.
	// This class will not be used with large sets, so O(n) is cheaper than O(1) -
	// at least it is on memory!
	private final LinkedList waitingFor;
	private final LinkedList waitingForBlockSet;
	private final PutCompletionCallback cb;
	private ClientPutState generator;
	private final BaseClientPutter parent;
	private InserterException e;
	private boolean finished;
	private boolean started;
	public final Object token;
	
	public MultiPutCompletionCallback(PutCompletionCallback cb, BaseClientPutter parent, Object token) {
		this.cb = cb;
		this.waitingFor = new LinkedList();
		this.waitingForBlockSet = new LinkedList();
		this.parent = parent;
		this.token = token;
		finished = false;
	}

	public void onSuccess(ClientPutState state) {
		synchronized(this) {
			if(finished) return;
			waitingFor.remove(state);
			if(!(waitingFor.isEmpty() && started))
				return;
		}
		/* Using this.e here will cause complete to consider the
		 * insert as failed if onFailed has been called in the past
		 * for this request. This makes collisions work. It does
		 * mean that onSuccess gets called, and then we consider
		 * the insert to have failed, which may or may not make sense.
		 */
		complete(this.e);
	}

	public void onFailure(InserterException e, ClientPutState state) {
		synchronized(this) {
			if(finished) return;
			waitingFor.remove(state);
			if(!(waitingFor.isEmpty() && started)) {
				this.e = e;
				return;
			}
		}
		complete(e);
	}

	private synchronized void complete(InserterException e) {
		finished = true;
		if(e != null)
			cb.onFailure(e, this);
		else
			cb.onSuccess(this);
	}

	public synchronized void addURIGenerator(ClientPutState ps) {
		add(ps);
		generator = ps;
	}
	
	public synchronized void add(ClientPutState ps) {
		if(finished) return;
		waitingFor.add(ps);
	}

	public void arm() {
		boolean allDone;
		boolean allGotBlocks;
		synchronized(this) {
			started = true;
			allDone = waitingFor.isEmpty();
			allGotBlocks = waitingForBlockSet.isEmpty();
		}
		if(allGotBlocks) {
			cb.onBlockSetFinished(this);
		}
		if(allDone) {
			complete(e);
		}
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void onEncode(BaseClientKey key, ClientPutState state) {
		synchronized(this) {
			if(state != generator) return;
		}
		cb.onEncode(key, this);
	}

	public void cancel() {
		ClientPutState[] states = new ClientPutState[waitingFor.size()];
		synchronized(this) {
			states = (ClientPutState[]) waitingFor.toArray(states);
		}
		for(int i=0;i<states.length;i++)
			states[i].cancel();
	}

	public synchronized void onTransition(ClientPutState oldState, ClientPutState newState) {
		if(generator == oldState)
			generator = newState;
		if(oldState == newState) return;
		for(ListIterator i = waitingFor.listIterator(0);i.hasNext();) {
			if(i.next() == oldState) {
				i.remove();
				i.add(newState);
			}
		}
	}

	public void onMetadata(Metadata m, ClientPutState state) {
		if(generator == state) {
			cb.onMetadata(m, this);
		} else {
			Logger.error(this, "Got metadata for "+state);
		}
	}

	public void onBlockSetFinished(ClientPutState state) {
		synchronized(this) {
			this.waitingForBlockSet.remove(state);
			if(!started) return;
		}
		cb.onBlockSetFinished(this);
	}

	public void schedule() throws InserterException {
		// Do nothing
	}

	public Object getToken() {
		return token;
	}

}
