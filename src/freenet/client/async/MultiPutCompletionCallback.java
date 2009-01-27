package freenet.client.async;

import java.util.Arrays;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class MultiPutCompletionCallback implements PutCompletionCallback, ClientPutState {

	// Vector's rather than HashSet's for memory reasons.
	// This class will not be used with large sets, so O(n) is cheaper than O(1) -
	// at least it is on memory!
	private final Vector waitingFor;
	private final Vector waitingForBlockSet;
	private final Vector waitingForFetchable;
	private final PutCompletionCallback cb;
	private ClientPutState generator;
	private final BaseClientPutter parent;
	private InsertException e;
	private boolean finished;
	private boolean started;
	public final Object token;
	private final boolean persistent;
	
	public void objectOnActivate(ObjectContainer container) {
		// Only activate the arrays
		container.activate(waitingFor, 1);
		container.activate(waitingForBlockSet, 1);
		container.activate(waitingForFetchable, 1);
	}
	
	public MultiPutCompletionCallback(PutCompletionCallback cb, BaseClientPutter parent, Object token) {
		this.cb = cb;
		waitingFor = new Vector();
		waitingForBlockSet = new Vector();
		waitingForFetchable = new Vector();
		this.parent = parent;
		this.token = token;
		finished = false;
		this.persistent = parent.persistent();
	}

	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		onBlockSetFinished(state, container, context);
		onFetchable(state, container);
		synchronized(this) {
			if(finished) return;
			waitingFor.remove(state);
			if(!(waitingFor.isEmpty() && started)) {
				if(persistent) {
					container.store(waitingFor);
				}
				return;
			}
		}
		Logger.minor(this, "Completing...");
		complete(null, container, context);
	}

	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			waitingFor.remove(state);
			waitingForBlockSet.remove(state);
			waitingForFetchable.remove(state);
			if(!(waitingFor.isEmpty() && started)) {
				if(persistent) {
					container.store(waitingFor);
					container.store(waitingForBlockSet);
					container.store(waitingForFetchable);
				}
				this.e = e;
				if(persistent)
					container.store(this);
				return;
			}
		}
		if(persistent) {
			container.store(waitingFor);
			container.store(waitingForBlockSet);
			container.store(waitingForFetchable);
		}
		complete(e, container, context);
	}

	private void complete(InsertException e, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
			if(e != null && this.e != null && this.e != e) {
				if(!(e.getMode() == InsertException.CANCELLED)) // Cancelled is okay, ignore it, we cancel after failure sometimes.
					Logger.error(this, "Completing with "+e+" but already set "+this.e);
			}
			if(e == null) e = this.e;
		}
		if(persistent) {
			container.store(this);
			container.activate(cb, 1);
		}
		if(e != null)
			cb.onFailure(e, this, container, context);
		else
			cb.onSuccess(this, container, context);
	}

	public synchronized void addURIGenerator(ClientPutState ps, ObjectContainer container) {
		add(ps, container);
		generator = ps;
		if(persistent)
			container.store(this);
	}
	
	public synchronized void add(ClientPutState ps, ObjectContainer container) {
		if(finished) return;
		waitingFor.add(ps);
		waitingForBlockSet.add(ps);
		waitingForFetchable.add(ps);
		if(persistent) {
			container.store(waitingFor);
			container.store(waitingForBlockSet);
			container.store(waitingForFetchable);
		}
	}

	public void arm(ObjectContainer container, ClientContext context) {
		boolean allDone;
		boolean allGotBlocks;
		synchronized(this) {
			started = true;
			allDone = waitingFor.isEmpty();
			allGotBlocks = waitingForBlockSet.isEmpty();
		}
		if(persistent) {
			container.store(this);
			container.activate(cb, 1);
		}
		if(allGotBlocks) {
			cb.onBlockSetFinished(this, container, context);
		}
		if(allDone) {
			complete(e, container, context);
		}
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(state != generator) return;
		}
		if(persistent)
			container.activate(cb, 1);
		cb.onEncode(key, this, container, context);
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		ClientPutState[] states = new ClientPutState[waitingFor.size()];
		synchronized(this) {
			states = (ClientPutState[]) waitingFor.toArray(states);
		}
		for(int i=0;i<states.length;i++) {
			if(persistent)
				container.activate(states[i], 1);
			states[i].cancel(container, context);
		}
	}

	public synchronized void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		if(generator == oldState)
			generator = newState;
		if(oldState == newState) return;
		for(int i=0;i<waitingFor.size();i++) {
			if(waitingFor.get(i) == oldState) {
				waitingFor.set(i, newState);
				container.store(waitingFor);
			}
		}
		for(int i=0;i<waitingFor.size();i++) {
			if(waitingForBlockSet.get(i) == oldState) {
				waitingForBlockSet.set(i, newState);
				container.store(waitingFor);
			}
		}
		for(int i=0;i<waitingFor.size();i++) {
			if(waitingForFetchable.get(i) == oldState) {
				waitingForFetchable.set(i, newState);
				container.store(waitingFor);
			}
		}
	}

	public synchronized void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(cb, 1);
		if(generator == state) {
			cb.onMetadata(m, this, container, context);
		} else {
			Logger.error(this, "Got metadata for "+state);
		}
	}

	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			this.waitingForBlockSet.remove(state);
			if(persistent)
				container.store(waitingForBlockSet);
			if(!started) return;
			if(!waitingForBlockSet.isEmpty()) return;
		}
		if(persistent)
			container.activate(cb, 1);
		cb.onBlockSetFinished(this, container, context);
	}

	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		// Do nothing
	}

	public Object getToken() {
		return token;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		synchronized(this) {
			this.waitingForFetchable.remove(state);
			if(persistent)
				container.store(waitingForFetchable);
			if(!started) return;
			if(!waitingForFetchable.isEmpty()) return;
		}
		if(persistent)
			container.activate(cb, 1);
		cb.onFetchable(this, container);
	}

}
