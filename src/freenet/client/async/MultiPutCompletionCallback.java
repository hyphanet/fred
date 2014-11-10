package freenet.client.async;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import freenet.client.InsertException;
import freenet.client.InsertException.InsertExceptionMode;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.support.ListUtils;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.ResumeFailedException;

public class MultiPutCompletionCallback implements PutCompletionCallback, ClientPutState, Serializable {

    private static final long serialVersionUID = 1L;
    private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	// ArrayLists rather than HashSet's for memory reasons.
	// This class will not be used with large sets, so O(n) is cheaper than O(1) -
	// at least it is on memory!
	private final ArrayList<ClientPutState> waitingFor;
	private final ArrayList<ClientPutState> waitingForBlockSet;
	private final ArrayList<ClientPutState> waitingForFetchable;
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
	private transient boolean resumed;
	private BaseClientKey encodedKey;
	
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
		waitingFor = new ArrayList<ClientPutState>();
		waitingForBlockSet = new ArrayList<ClientPutState>();
		waitingForFetchable = new ArrayList<ClientPutState>();
		this.parent = parent;
		this.token = token;
		cancelling = false;
		finished = false;
		this.persistent = persistent;
	}

	@Override
	public void onSuccess(ClientPutState state, ClientContext context) {
		onBlockSetFinished(state, context);
		onFetchable(state);
		boolean complete = true;
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Already finished but got onSuccess() for "+state+" on "+this);
				return;
			}
			ListUtils.removeBySwapLast(waitingFor,state);
			ListUtils.removeBySwapLast(waitingForBlockSet,state);
			ListUtils.removeBySwapLast(waitingForFetchable,state);
			if(!(waitingFor.isEmpty() && started)) {
				complete = false;
			}
			if(state == generator) {
				generator = null;
			}
		}
		if(complete) {
			Logger.minor(this, "Completing...");
			complete(null, context);
		}
	}

	@Override
	public void onFailure(InsertException e, ClientPutState state, ClientContext context) {
		if(collisionIsOK && e.getMode() == InsertExceptionMode.COLLISION) {
			onSuccess(state, context);
			return;
		}
		boolean complete = true;
		boolean doCancel = false;
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Already finished but got onFailure() for "+state+" on "+this);
				return;
			}
			ListUtils.removeBySwapLast(waitingFor,state);
			ListUtils.removeBySwapLast(waitingForBlockSet,state);
			ListUtils.removeBySwapLast(waitingForFetchable,state);
			if(!(waitingFor.isEmpty() && started)) {
				this.e = e;
				if(logMINOR) Logger.minor(this, "Still running: "+waitingFor.size()+" started = "+started);
				complete = false;
			}
			if(state == generator) {
				generator = null;
			}
			if(finishOnFailure) {
				if(started)
					doCancel = true;
				else {
					cancelling = true;
				}
			}
		}
		if(complete)
			complete(e, context);
		else if(doCancel)
			cancel(context);
	}

	private void complete(InsertException e, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
			if(e != null && this.e != null && this.e != e) {
				if(e.getMode() == InsertExceptionMode.CANCELLED) { // Cancelled is okay, ignore it, we cancel after failure sometimes.
					// Ignore the new failure mode, use the old one
					e = this.e;
					if(persistent) {
						e = e.clone(); // Since we will remove it, we can't pass it on
					}
				} else {
					// Delete the old failure mode, use the new one
					this.e = e;
				}
				
			}
			if(e == null) {
				e = this.e;
				if(persistent && e != null) {
					e = e.clone(); // Since we will remove it, we can't pass it on
				}
			}
		}
		if(e != null)
			cb.onFailure(e, this, context);
		else
			cb.onSuccess(this, context);
	}

	public synchronized void addURIGenerator(ClientPutState ps) {
		add(ps);
		generator = ps;
	}
	
	public synchronized void add(ClientPutState ps) {
		if(finished) return;
		waitingFor.add(ps);
		waitingForBlockSet.add(ps);
		waitingForFetchable.add(ps);
	}

	public void arm(ClientContext context) {
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
		if(allGotBlocks) {
			cb.onBlockSetFinished(this, context);
		}
		if(allDone) {
			complete(e, context);
		} else if(doCancel) {
			cancel(context);
		}
	}

	@Override
	public BaseClientPutter getParent() {
		return parent;
	}

	@Override
	public void onEncode(BaseClientKey key, ClientPutState state, ClientContext context) {
		synchronized(this) {
			if(state != generator) return;
			if(encodedKey != null) {
			    if(key.equals(encodedKey)) return; // Squash duplicated call to onEncode().
			    else Logger.error(this, "Encoded twice with different keys for "+this+" : "+encodedKey+" -> "+key);
			}
			encodedKey = key;
		}
		cb.onEncode(key, this, context);
	}

	@Override
	public void cancel(ClientContext context) {
		ClientPutState[] states = new ClientPutState[waitingFor.size()];
		synchronized(this) {
			states = waitingFor.toArray(states);
		}
		boolean logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
		for(int i=0;i<states.length;i++) {
			if(logDEBUG) Logger.minor(this, "Cancelling state "+i+" of "+states.length+" : "+states[i]);
			states[i].cancel(context);
		}
	}

	@Override
	public synchronized void onTransition(ClientPutState oldState, ClientPutState newState, ClientContext context) {
		if(generator == oldState)
			generator = newState;
		if(oldState == newState) return;
		for(int i=0;i<waitingFor.size();i++) {
			if(waitingFor.get(i) == oldState) {
				waitingFor.set(i, newState);
			}
		}
		for(int i=0;i<waitingForBlockSet.size();i++) {
			if(waitingForBlockSet.get(i) == oldState) {
				waitingForBlockSet.set(i, newState);
			}
		}
		for(int i=0;i<waitingForFetchable.size();i++) {
			if(waitingForFetchable.get(i) == oldState) {
				waitingForFetchable.set(i, newState);
			}
		}
	}

	@Override
	public synchronized void onMetadata(Metadata m, ClientPutState state, ClientContext context) {
		if(generator == state) {
			cb.onMetadata(m, this, context);
		} else {
			Logger.error(this, "Got metadata for "+state);
		}
	}
	
	@Override
	public synchronized void onMetadata(Bucket metadata, ClientPutState state, ClientContext context) {
		if(generator == state) {
			cb.onMetadata(metadata, this, context);
		} else {
			Logger.error(this, "Got metadata for "+state);
		}
	}

	@Override
	public void onBlockSetFinished(ClientPutState state, ClientContext context) {
		synchronized(this) {
			ListUtils.removeBySwapLast(this.waitingForBlockSet,state);
			if(!started) return;
			if(!waitingForBlockSet.isEmpty()) return;
		}
		cb.onBlockSetFinished(this, context);
	}

	@Override
	public void schedule(ClientContext context) throws InsertException {
		// Do nothing
	}

	@Override
	public Object getToken() {
		return token;
	}

	@Override
	public void onFetchable(ClientPutState state) {
		synchronized(this) {
			ListUtils.removeBySwapLast(this.waitingForFetchable,state);
			if(!started) return;
			if(!waitingForFetchable.isEmpty()) return;
			if(calledFetchable) {
				if(logMINOR) Logger.minor(this, "Trying to call onFetchable() twice");
				return;
			}
			calledFetchable = true;
		}
		cb.onFetchable(this);
	}

    @Override
    public void onResume(ClientContext context) throws InsertException, ResumeFailedException {
        synchronized(this) {
            if(resumed) return;
            resumed = true;
        }
        for(ClientPutState s : getWaitingFor())
            s.onResume(context);
        if(cb != parent) cb.onResume(context);
    }

    @Override
    public void onShutdown(ClientContext context) {
        for(ClientPutState state : getWaitingFor()) {
            state.onShutdown(context);
        }
    }

    private synchronized List<ClientPutState> getWaitingFor() {
        return new ArrayList<ClientPutState>(waitingFor);
    }

}
