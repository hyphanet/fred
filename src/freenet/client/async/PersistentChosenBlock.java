package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestSender;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * A block within a PersistentChosenRequest.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class PersistentChosenBlock extends ChosenBlock {
	
	public final PersistentChosenRequest parent;
	public final boolean isInsert;
	
	/* Completion */
	private boolean finished;
	
	/** If a SendableGet failed, failedGet will be set to the exception generated. Cannot be null if it failed. */
	private LowLevelGetException failedGet;
	
	private boolean fetchSucceeded; // The actual block is not our problem.
	
	/* Inserts */
	private boolean insertSucceeded;
	/** If a SendableInsert failed, failedPut will be set to the exception generated. Cannot be null if it failed. */
	private LowLevelPutException failedPut;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public PersistentChosenBlock(boolean isInsert, PersistentChosenRequest parent, SendableRequestItem token, Key key, ClientKey ckey, RequestScheduler sched) {
		super(token, key, ckey, parent.localRequestOnly, parent.ignoreStore, parent.canWriteClientCache, parent.forkOnCacheable, parent.realTimeFlag, sched);
		this.isInsert = isInsert;
		this.parent = parent;
		if(logMINOR) Logger.minor(this, "Created "+this+" for "+parent+" ckey="+ckey);
	}
	
	@Override
	public void onFetchSuccess(ClientContext context) {
		assert(!isInsert);
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Already finished in onSuccess() on "+this, new Exception("debug"));
				return;
			}
			finished = true;
			fetchSucceeded = true;
		}
		parent.onFinished(this, context);
		parent.scheduler.succeeded((SendableGet)parent.request, true);
	}

	@Override
	public void onFailure(LowLevelGetException e, ClientContext context) {
		assert(!isInsert);
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Already finished in onFailure() on "+this, new Exception("debug"));
				return;
			}
			if(e == null)
				throw new NullPointerException();
			failedGet = e;
			finished = true;
		}
		parent.onFinished(this, context);
	}
	
	@Override
	public void onInsertSuccess(ClientContext context) {
		assert(isInsert);
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Already finished in onSuccess() on "+this, new Exception("debug"));
				return;
			}
			insertSucceeded = true;
			finished = true;
			if(getGeneratedKey() == null)
				Logger.error(this, "Insert completed but no generated key on "+this, new Exception("debug"));
		}
		parent.onFinished(this, context);
	}
	
	@Override
	public void onFailure(LowLevelPutException e, ClientContext context) {
		assert(isInsert);
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Already finished in onFailure() on "+this, new Exception("debug"));
				return;
			}
			if(e == null)
				throw new NullPointerException();
			failedPut = e;
			finished = true;
		}
		parent.onFinished(this, context);
	}
	
	LowLevelGetException failedGet() {
		return failedGet;
	}
	
	boolean insertSucceeded() {
		return insertSucceeded;
	}
	
	boolean fetchSucceeded() {
		return fetchSucceeded;
	}
	
	LowLevelPutException failedPut() {
		return failedPut;
	}

	@Override
	public boolean isPersistent() {
		return true;
	}

	@Override
	public boolean isCancelled() {
		// We can't tell without accesing the database, and we can't access the database on the request starter thread.
		return false;
	}

	@Override
	public boolean send(NodeClientCore core, RequestScheduler sched) {
		try {
			return super.send(core, sched);
		} finally {
			boolean wasFinished;
			synchronized(this) {
				wasFinished = finished;
				if(!finished) {
					finished = true;
					if(parent.request instanceof SendableGet) {
						Logger.error(this, "SendableGet "+parent.request+" didn't call a callback on "+this);
					}
				}
			}
			if(!wasFinished) {
				parent.onFinished(this, sched.getContext());
			}
		}
	}

	@Override
	public short getPriority() {
		return parent.prio;
	}

	@Override
	public SendableRequestSender getSender(ClientContext context) {
		return parent.sender;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Trying to store a PersistentChosenRequest!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanUpdate(ObjectContainer container) {
		Logger.error(this, "Trying to store a PersistentChosenRequest!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanActivate(ObjectContainer container) {
		Logger.error(this, "Trying to store a PersistentChosenRequest!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanDeactivate(ObjectContainer container) {
		Logger.error(this, "Trying to store a PersistentChosenRequest!", new Exception("error"));
		return false;
	}
	
}
