package freenet.client.async;

import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class MultiPutCompletionCallback implements PutCompletionCallback, ClientPutState {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	// Vector's rather than HashSet's for memory reasons.
	// This class will not be used with large sets, so O(n) is cheaper than O(1) -
	// at least it is on memory!
	private final Vector<ClientPutState> waitingFor;
	private final Vector<ClientPutState> waitingForBlockSet;
	private final Vector<ClientPutState> waitingForFetchable;
	private final PutCompletionCallback cb;
	private ClientPutState generator;
	private final BaseClientPutter parent;
	private InsertException e;
	private boolean cancelling;
	private boolean finished;
	private boolean started;
	private boolean calledFetchable;
	public final Object token;
	private final boolean persistent;
	private final boolean collisionIsOK;
	private final boolean finishOnFailure;
	
	public void objectOnActivate(ObjectContainer container) {
		// Only activate the arrays
		container.activate(waitingFor, 1);
		container.activate(waitingForBlockSet, 1);
		container.activate(waitingForFetchable, 1);
	}
	
	public MultiPutCompletionCallback(PutCompletionCallback cb, BaseClientPutter parent, Object token, boolean persistent) {
		this(cb, parent, token, persistent, false);
	}
	
	public MultiPutCompletionCallback(PutCompletionCallback cb, BaseClientPutter parent, Object token, boolean persistent, boolean collisionIsOK) {
		this(cb, parent, token, persistent, collisionIsOK, false);
	}
	
	public MultiPutCompletionCallback(PutCompletionCallback cb, BaseClientPutter parent, Object token, boolean persistent, boolean collisionIsOK, boolean finishOnFailure) {
		this.cb = cb;
		this.collisionIsOK = collisionIsOK;
		this.finishOnFailure = finishOnFailure;
		waitingFor = new Vector<ClientPutState>();
		waitingForBlockSet = new Vector<ClientPutState>();
		waitingForFetchable = new Vector<ClientPutState>();
		this.parent = parent;
		this.token = token;
		cancelling = false;
		finished = false;
		this.persistent = persistent;
	}

	@Override
	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		onBlockSetFinished(state, container, context);
		onFetchable(state, container);
		if(persistent)
			container.activate(waitingFor, 2);
		boolean complete = true;
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Already finished but got onSuccess() for "+state+" on "+this);
				return;
			}
			waitingFor.remove(state);
			waitingForBlockSet.remove(state);
			waitingForFetchable.remove(state);
			if(!(waitingFor.isEmpty() && started)) {
				if(persistent) {
					container.ext().store(waitingFor, 1);
				}
				complete = false;
			}
			if(state == generator) {
				generator = null;
				if(persistent) container.store(this);
			}
		}
		if(persistent) {
			container.ext().store(waitingFor, 2);
			container.ext().store(waitingForBlockSet, 2);
			container.ext().store(waitingForFetchable, 2);
			state.removeFrom(container, context);
		}
		if(complete) {
			Logger.minor(this, "Completing...");
			complete(null, container, context);
		}
	}

	@Override
	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(collisionIsOK && e.getMode() == InsertException.COLLISION) {
			onSuccess(state, container, context);
			return;
		}
		if(persistent) {
			container.activate(waitingFor, 2);
			container.activate(waitingForBlockSet, 2);
			container.activate(waitingForFetchable, 2);
		}
		boolean complete = true;
		boolean doCancel = false;
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Already finished but got onFailure() for "+state+" on "+this);
				return;
			}
			waitingFor.remove(state);
			waitingForBlockSet.remove(state);
			waitingForFetchable.remove(state);
			if(!(waitingFor.isEmpty() && started)) {
				if(this.e != null) {
					if(persistent) {
						container.activate(this.e, 10);
						this.e.removeFrom(container);
					}
				}
				this.e = e;
				if(persistent)
					container.store(this);
				if(logMINOR) Logger.minor(this, "Still running: "+waitingFor.size()+" started = "+started);
				complete = false;
			}
			if(state == generator) {
				generator = null;
				if(persistent) container.store(this);
			}
			if(finishOnFailure) {
				if(started)
					doCancel = true;
				else {
					cancelling = true;
					if(persistent)
						container.store(this);
				}
			}
		}
		if(persistent) {
			container.ext().store(waitingFor, 2);
			container.ext().store(waitingForBlockSet, 2);
			container.ext().store(waitingForFetchable, 2);
			state.removeFrom(container, context);
		}
		if(complete)
			complete(e, container, context);
		else if(doCancel)
			cancel(container, context);
	}

	private void complete(InsertException e, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
			if(e != null && this.e != null && this.e != e) {
				if(persistent) container.activate(this.e, 10);
				if(e.getMode() == InsertException.CANCELLED) { // Cancelled is okay, ignore it, we cancel after failure sometimes.
					// Ignore the new failure mode, use the old one
					e = this.e;
					if(persistent) {
						container.activate(e, 5);
						e = e.clone(); // Since we will remove it, we can't pass it on
					}
				} else {
					// Delete the old failure mode, use the new one
					if(persistent)
						this.e.removeFrom(container);
					this.e = e;
				}
				
			}
			if(e == null) {
				e = this.e;
				if(persistent && e != null) {
					container.activate(e, 10);
					e = e.clone(); // Since we will remove it, we can't pass it on
				}
			}
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
			container.store(ps);
			container.ext().store(waitingFor, 2);
			container.ext().store(waitingForBlockSet, 2);
			container.ext().store(waitingForFetchable, 2);
		}
	}

	public void arm(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Arming "+this);
		boolean allDone;
		boolean allGotBlocks;
		boolean doCancel;
		synchronized(this) {
			started = true;
			allDone = waitingFor.isEmpty();
			allGotBlocks = waitingForBlockSet.isEmpty();
			doCancel = cancelling;
		}
		if(persistent) {
			container.store(this);
			container.activate(cb, 1);
		}
		if(allGotBlocks) {
			cb.onBlockSetFinished(this, container, context);
		}
		if(allDone) {
			if(persistent && e != null) container.activate(e, 5);
			complete(e, container, context);
		} else if(doCancel) {
			cancel(container, context);
		}
	}

	@Override
	public BaseClientPutter getParent() {
		return parent;
	}

	@Override
	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(state != generator) return;
		}
		if(persistent)
			container.activate(cb, 1);
		cb.onEncode(key, this, container, context);
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		ClientPutState[] states = new ClientPutState[waitingFor.size()];
		synchronized(this) {
			states = waitingFor.toArray(states);
		}
		boolean logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
		for(int i=0;i<states.length;i++) {
			if(persistent)
				container.activate(states[i], 1);
			if(logDEBUG) Logger.minor(this, "Cancelling state "+i+" of "+states.length+" : "+states[i]);
			states[i].cancel(container, context);
		}
	}

	@Override
	public synchronized void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		if(generator == oldState)
			generator = newState;
		if(oldState == newState) return;
		for(int i=0;i<waitingFor.size();i++) {
			if(waitingFor.get(i) == oldState) {
				waitingFor.set(i, newState);
				if(persistent) container.ext().store(waitingFor, 2);
			}
		}
		for(int i=0;i<waitingForBlockSet.size();i++) {
			if(waitingForBlockSet.get(i) == oldState) {
				waitingForBlockSet.set(i, newState);
				if(persistent) container.ext().store(waitingForBlockSet, 2);
			}
		}
		for(int i=0;i<waitingForFetchable.size();i++) {
			if(waitingForFetchable.get(i) == oldState) {
				waitingForFetchable.set(i, newState);
				if(persistent) container.ext().store(waitingForFetchable, 2);
			}
		}
		if(persistent) container.store(this);
	}

	@Override
	public synchronized void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(cb, 1);
		if(generator == state) {
			cb.onMetadata(m, this, container, context);
		} else {
			Logger.error(this, "Got metadata for "+state);
		}
	}
	
	@Override
	public synchronized void onMetadata(Bucket metadata, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(cb, 1);
		if(generator == state) {
			cb.onMetadata(metadata, this, container, context);
		} else {
			Logger.error(this, "Got metadata for "+state);
		}
	}

	@Override
	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(waitingForBlockSet, 2);
		synchronized(this) {
			this.waitingForBlockSet.remove(state);
			if(persistent)
				container.ext().store(waitingForBlockSet, 2);
			if(!started) return;
			if(!waitingForBlockSet.isEmpty()) return;
		}
		if(persistent)
			container.activate(cb, 1);
		cb.onBlockSetFinished(this, container, context);
	}

	@Override
	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		// Do nothing
	}

	@Override
	public Object getToken() {
		return token;
	}

	@Override
	public void onFetchable(ClientPutState state, ObjectContainer container) {
		if(persistent)
			container.activate(waitingForFetchable, 2);
		synchronized(this) {
			this.waitingForFetchable.remove(state);
			if(persistent)
				container.ext().store(waitingForFetchable, 2);
			if(!started) return;
			if(!waitingForFetchable.isEmpty()) return;
			if(calledFetchable) {
				if(logMINOR) Logger.minor(this, "Trying to call onFetchable() twice");
				return;
			}
			calledFetchable = true;
		}
		if(persistent) {
			container.ext().store(this, 1);
			container.activate(cb, 1);
		}
		cb.onFetchable(this, container);
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.activate(waitingFor, 2);
		container.activate(waitingForBlockSet, 2);
		container.activate(waitingForFetchable, 2);
		// Should have been cleared by now
		if(!waitingFor.isEmpty())
			Logger.error(this, "waitingFor not empty in removeFrom() on "+this+" : "+waitingFor);
		if(!waitingForBlockSet.isEmpty())
			Logger.error(this, "waitingForBlockSet not empty in removeFrom() on "+this+" : "+waitingForBlockSet);
		if(!waitingForFetchable.isEmpty())
			Logger.error(this, "waitingForFetchable not empty in removeFrom() on "+this+" : "+waitingForFetchable);
		container.delete(waitingFor);
		container.delete(waitingForBlockSet);
		container.delete(waitingForFetchable);
		// cb is at a higher level, we don't remove that, it removes itself
		// generator is just a reference to one of the waitingFor's
		// parent removes itself
		if(e != null) {
			container.activate(e, 5);
			e.removeFrom(container);
		}
		// whoever set the token is responsible for removing it
		container.delete(this);
	}

}
