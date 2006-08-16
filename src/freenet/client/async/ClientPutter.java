package freenet.client.async;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;

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
	private boolean startedStarting;
	private FreenetURI uri;
	/** SimpleFieldSet containing progress information from last startup.
	 * Will be progressively cleared during startup. */
	private SimpleFieldSet oldProgress;

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
	 * @param stored The progress so far, stored as a SimpleFieldSet. Advisory; if there 
	 * is an error reading this in, we will restart from scratch.
	 */
	public ClientPutter(ClientCallback client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InserterContext ctx,
			ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, short priorityClass, boolean getCHKOnly, 
			boolean isMetadata, Object clientContext, SimpleFieldSet stored) {
		super(priorityClass, chkScheduler, sskScheduler, clientContext);
		this.cm = cm;
		this.isMetadata = isMetadata;
		this.getCHKOnly = getCHKOnly;
		this.client = client;
		this.data = data;
		this.targetURI = targetURI;
		this.ctx = ctx;
		this.finished = false;
		this.cancelled = false;
		this.oldProgress = stored;
	}

	public void start() throws InserterException {
		Logger.minor(this, "Starting "+this);
		try {
			boolean cancel = false;
			synchronized(this) {
				if(startedStarting) return;
				startedStarting = true;
				if(currentState != null) return;
				cancel = this.cancelled;
				if(!cancel) {
					currentState =
						new SingleFileInserter(this, this, new InsertBlock(data, cm, targetURI), isMetadata, ctx, false, getCHKOnly, false, null, false, false);
				}
			}
			if(cancel) {
				onFailure(new InserterException(InserterException.CANCELLED), null);
				oldProgress = null;
				return;
			}
			synchronized(this) {
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InserterException(InserterException.CANCELLED), null);
				oldProgress = null;
				return;
			}
			((SingleFileInserter)currentState).start(oldProgress);
			synchronized(this) {
				oldProgress = null;
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InserterException(InserterException.CANCELLED), null);
				return;
			}
		} catch (InserterException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(e, this);
			}
		}
		Logger.minor(this, "Started "+this);
	}

	public void onSuccess(ClientPutState state) {
		synchronized(this) {
			finished = true;
			currentState = null;
			oldProgress = null;
		}
		if(super.failedBlocks > 0 || super.fatallyFailedBlocks > 0 || super.successfulBlocks < super.totalBlocks) {
			Logger.error(this, "Failed blocks: "+failedBlocks+", Fatally failed blocks: "+fatallyFailedBlocks+
					", Successful blocks: "+successfulBlocks+", Total blocks: "+totalBlocks+" but success?! on "+this+" from "+state,
					new Exception("debug"));
		}
		client.onSuccess(this);
	}

	public void onFailure(InserterException e, ClientPutState state) {
		synchronized(this) {
			finished = true;
			currentState = null;
			oldProgress = null;
		}
		client.onFailure(e, this);
	}

	public void onMajorProgress() {
		client.onMajorProgress();
	}
	
	public void onEncode(BaseClientKey key, ClientPutState state) {
		this.uri = key.getURI();
		client.onGeneratedURI(uri, this);
	}
	
	public void cancel() {
		synchronized(this) {
			if(cancelled) return;
			super.cancel();
			if(currentState != null)
				currentState.cancel();
			if(startedStarting) return;
			startedStarting = true;
		}
		onFailure(new InserterException(InserterException.CANCELLED), null);
	}
	
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	public FreenetURI getURI() {
		return uri;
	}

	public synchronized void onTransition(ClientPutState oldState, ClientPutState newState) {
		if(newState == null) throw new NullPointerException();
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

	public SimpleFieldSet getProgressFieldset() {
		if(currentState == null) return null;
		return currentState.getProgressFieldset();
	}
	
}
