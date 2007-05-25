/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
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
	final InsertContext ctx;
	final String targetFilename;
	private ClientPutState currentState;
	private boolean finished;
	private final boolean getCHKOnly;
	private final boolean isMetadata;
	private boolean startedStarting;
	private final boolean binaryBlob;
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
	public ClientPutter(ClientCallback client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InsertContext ctx,
			ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, short priorityClass, boolean getCHKOnly, 
			boolean isMetadata, Object clientContext, SimpleFieldSet stored, String targetFilename, boolean binaryBlob) {
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
		this.binaryBlob = binaryBlob;
	}

	public void start(boolean earlyEncode) throws InsertException {
		start(earlyEncode, false);
	}
	
	public boolean start(boolean earlyEncode, boolean restart) throws InsertException {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting "+this);
		try {
			this.targetURI.checkInsertURI();
			
			if(data == null)
				throw new InsertException(InsertException.BUCKET_ERROR, "No data to insert", null);
			
			boolean cancel = false;
			synchronized(this) {
				if(restart) {
					if(currentState != null && !finished) return false;
					finished = false;
				}
				if(startedStarting) return false;
				startedStarting = true;
				if(currentState != null) return false;
				cancel = this.cancelled;
				if(!cancel) {
					if(!binaryBlob)
						currentState =
							new SingleFileInserter(this, this, new InsertBlock(data, cm, targetURI), isMetadata, ctx, 
									false, getCHKOnly, false, null, false, false, targetFilename, earlyEncode);
					else
						currentState =
							new BinaryBlobInserter(data, this, null, false, priorityClass, ctx);
				}
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null);
				oldProgress = null;
				return false;
			}
			synchronized(this) {
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null);
				oldProgress = null;
				return false;
			}
			((SingleFileInserter)currentState).start(oldProgress);
			synchronized(this) {
				oldProgress = null;
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null);
				return false;
			}
		} catch (InsertException e) {
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
		} catch (IOException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), this);
			}
		} catch (BinaryBlobFormatException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(new InsertException(InsertException.BINARY_BLOB_FORMAT_ERROR, e, null), this);
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

	public void onFailure(InsertException e, ClientPutState state) {
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
		ClientPutState oldState = null;
		synchronized(this) {
			if(cancelled) return;
			super.cancel();
			oldState = currentState;
			if(startedStarting) return;
			startedStarting = true;
		}
		if(oldState != null) oldState.cancel();
		onFailure(new InsertException(InsertException.CANCELLED), null);
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

	public boolean restart(boolean earlyEncode) throws InsertException {
		return start(earlyEncode, true);
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore, at the moment
	}

}
