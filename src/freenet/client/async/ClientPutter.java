/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
import freenet.support.api.Bucket;

public class ClientPutter extends BaseClientPutter implements PutCompletionCallback {

	final ClientCallback client;
	final Bucket data;
	final FreenetURI targetURI;
	final ClientMetadata cm;
	final InserterContext ctx;
	final String targetFilename;
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
	 * @param targetFilename If set, create a one-file manifest containing this filename pointing to this file.
	 */
	public ClientPutter(ClientCallback client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InserterContext ctx,
			ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, short priorityClass, boolean getCHKOnly, 
			boolean isMetadata, Object clientContext, SimpleFieldSet stored, String targetFilename) {
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
		this.targetFilename = targetFilename;
	}

	public void start(boolean earlyEncode) throws InserterException {
		start(earlyEncode, false);
	}
	
	public boolean start(boolean earlyEncode, boolean restart) throws InserterException {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting "+this);
		try {
			this.targetURI.checkInsertURI();
			
			if(data == null)
				throw new InserterException(InserterException.BUCKET_ERROR, "No data to insert", null);
			
			boolean cancel = false;
			synchronized(this) {
				if(restart) {
					if(currentState != null && !finished) return false;
				}
				if(startedStarting) return false;
				startedStarting = true;
				if(currentState != null) return false;
				cancel = this.cancelled;
				if(!cancel) {
					currentState =
						new SingleFileInserter(this, this, new InsertBlock(data, cm, targetURI), isMetadata, ctx, false, getCHKOnly, false, null, false, false, targetFilename, earlyEncode);
				}
			}
			if(cancel) {
				onFailure(new InserterException(InserterException.CANCELLED), null);
				oldProgress = null;
				return false;
			}
			synchronized(this) {
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InserterException(InserterException.CANCELLED), null);
				oldProgress = null;
				return false;
			}
			((SingleFileInserter)currentState).start(oldProgress);
			synchronized(this) {
				oldProgress = null;
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InserterException(InserterException.CANCELLED), null);
				return false;
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
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Started "+this);
		return true;
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
		synchronized(this) {
			this.uri = key.getURI();
			if(targetFilename != null)
				uri = uri.pushMetaString(targetFilename);
		}
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
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized();
	}

	public SimpleFieldSet getProgressFieldset() {
		if(currentState == null) return null;
		return currentState.getProgressFieldset();
	}

	public void onFetchable(ClientPutState state) {
		client.onFetchable(this);
	}

	public boolean canRestart() {
		if(currentState != null && !finished) {
			Logger.minor(this, "Cannot restart because not finished for "+uri);
			return false;
		}
		if(data == null) return false;
		return true;
	}

	public boolean restart(boolean earlyEncode) throws InserterException {
		return start(earlyEncode, true);
	}

}
