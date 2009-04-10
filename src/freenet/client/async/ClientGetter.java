/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * A high level data request.
 */
public class ClientGetter extends BaseClientGetter {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}
	
	final ClientCallback clientCallback;
	FreenetURI uri;
	final FetchContext ctx;
	final ArchiveContext actx;
	private ClientGetState currentState;
	private boolean finished;
	private int archiveRestarts;
	/** If not null, Bucket to return the data in */
	final Bucket returnBucket;
	/** If not null, Bucket to return a binary blob in */
	final Bucket binaryBlobBucket;
	/** If not null, HashSet to track keys already added for a binary blob */
	final HashSet binaryBlobKeysAddedAlready;
	private DataOutputStream binaryBlobStream;
	private String expectedMIME;
	private long expectedSize;
	private boolean finalizedMetadata;
	
	/**
	 * Fetch a key.
	 * @param client
	 * @param sched
	 * @param uri
	 * @param ctx
	 * @param priorityClass
	 * @param clientContext The context object (can be anything). Used for round-robin query balancing.
	 * @param returnBucket The bucket to return the data in. Can be null. If not null, the ClientGetter must either
	 * write the data directly to the bucket, or copy it and free the original temporary bucket. Preferably the
	 * former, obviously!
	 */
	public ClientGetter(ClientCallback client, 
			    FreenetURI uri, FetchContext ctx, short priorityClass, RequestClient clientContext, Bucket returnBucket, Bucket binaryBlobBucket) {
		super(priorityClass, clientContext);
		this.clientCallback = client;
		this.returnBucket = returnBucket;
		this.uri = uri;
		this.ctx = ctx;
		this.finished = false;
		this.actx = new ArchiveContext(ctx.maxTempLength, ctx.maxArchiveLevels);
		this.binaryBlobBucket = binaryBlobBucket;
		if(binaryBlobBucket != null) {
			binaryBlobKeysAddedAlready = new HashSet();
		} else
			binaryBlobKeysAddedAlready = null;
		archiveRestarts = 0;
	}

	public void start(ObjectContainer container, ClientContext context) throws FetchException {
		start(false, null, container, context);
	}

	public boolean start(boolean restart, FreenetURI overrideURI, ObjectContainer container, ClientContext context) throws FetchException {
		if(persistent())
			container.activate(uri, 5);
		if(logMINOR)
			Logger.minor(this, "Starting "+this+" persistent="+persistent());
		try {
			// FIXME synchronization is probably unnecessary.
			// But we DEFINITELY do not want to synchronize while calling currentState.schedule(),
			// which can call onSuccess and thereby almost anything.
			synchronized(this) {
				if(overrideURI != null) uri = overrideURI;
				if(finished) {
					if(!restart) return false;
					currentState = null;
					cancelled = false;
					finished = false;
				}
				currentState = SingleFileFetcher.create(this, this, 
						uri, ctx, actx, ctx.maxNonSplitfileRetries, 0, false, -1, true,
						returnBucket, true, container, context);
			}
			if(cancelled) cancel();
			// schedule() may deactivate stuff, so store it now.
			if(persistent()) {
				container.store(currentState);
				container.store(this);
			}
			if(currentState != null && !finished) {
				if(binaryBlobBucket != null) {
					try {
						binaryBlobStream = new DataOutputStream(new BufferedOutputStream(binaryBlobBucket.getOutputStream()));
						BinaryBlob.writeBinaryBlobHeader(binaryBlobStream);
					} catch (IOException e) {
						onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to open binary blob bucket", e), null, container, context);
						if(persistent())
							container.store(this);
						return false;
					}
				}
				currentState.schedule(container, context);
			}
			if(cancelled) cancel();
		} catch (MalformedURLException e) {
			throw new FetchException(FetchException.INVALID_URI, e);
		} catch (KeyListenerConstructionException e) {
			onFailure(e.getFetchException(), currentState, container, context);
		}
		if(persistent()) {
			container.store(this);
			container.deactivate(currentState, 1);
		}
		return true;
	}

	public void onSuccess(FetchResult result, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Succeeded from "+state+" on "+this);
		if(persistent())
			container.activate(uri, 5);
		if(!closeBinaryBlobStream(container, context)) return;
		synchronized(this) {
			finished = true;
			currentState = null;
		}
		if(persistent()) {
			container.store(this);
		}
		// Rest of method does not need to be synchronized.
		// Variables will be updated on exit of method, and the only thing that is
		// set is the returnBucket and the result. Not locking not only prevents
		// nested locking resulting in deadlocks, it also prevents long locks due to
		// doing massive encrypted I/Os while holding a lock.
		if((returnBucket != null) && (result.asBucket() != returnBucket)) {
			Bucket from = result.asBucket();
			Bucket to = returnBucket;
			try {
				if(logMINOR)
					Logger.minor(this, "Copying - returnBucket not respected by client.async");
				if(persistent()) {
					container.activate(from, 5);
					container.activate(returnBucket, 5);
				}
				BucketTools.copy(from, to);
				from.free();
				if(persistent())
					from.removeFrom(container);
			} catch (IOException e) {
				Logger.error(this, "Error copying from "+from+" to "+to+" : "+e.toString(), e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e.toString()), state /* not strictly to blame, but we're not ako ClientGetState... */, container, context);
				return;
			}
			result = new FetchResult(result, to);
		} else {
			if(returnBucket != null && logMINOR)
				Logger.minor(this, "client.async returned data in returnBucket");
		}
		if(persistent()) {
			container.activate(state, 1);
			state.removeFrom(container, context);
			container.activate(clientCallback, 1);
		}
		FetchResult res = result;
		clientCallback.onSuccess(res, ClientGetter.this, container);
	}

	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Failed from "+state+" : "+e+" on "+this, e);
		closeBinaryBlobStream(container, context);
		if(persistent())
			container.activate(uri, 5);
		ClientGetState oldState = null;
		while(true) {
			if(e.mode == FetchException.ARCHIVE_RESTART) {
				int ar;
				synchronized(this) {
					archiveRestarts++;
					ar = archiveRestarts;
				}
				if(logMINOR)
					Logger.minor(this, "Archive restart on "+this+" ar="+ar);
				if(ar > ctx.maxArchiveRestarts)
					e = new FetchException(FetchException.TOO_MANY_ARCHIVE_RESTARTS);
				else {
					try {
						start(container, context);
					} catch (FetchException e1) {
						e = e1;
						continue;
					}
					return;
				}
			}
			synchronized(this) {
				finished = true;
				oldState = currentState;
				currentState = null;
			}
			if(e.errorCodes != null && e.errorCodes.isOneCodeOnly())
				e = new FetchException(e.errorCodes.getFirstCode(), e);
			if(e.mode == FetchException.DATA_NOT_FOUND && super.successfulBlocks > 0)
				e = new FetchException(e, FetchException.ALL_DATA_NOT_FOUND);
			if(logMINOR) Logger.minor(this, "onFailure("+e+", "+state+") on "+this+" for "+uri, e);
			final FetchException e1 = e;
			if(persistent())
				container.store(this);
			if(persistent()) {
				container.activate(clientCallback, 1);
			}
			clientCallback.onFailure(e1, ClientGetter.this, container);
			break;
		}
		if(persistent()) {
			if(state != null) {
				container.activate(state, 1);
				state.removeFrom(container, context);
			}
			if(oldState != state && oldState != null) {
				container.activate(oldState, 1);
				oldState.removeFrom(container, context);
			}
		}
	}

	/**
	 * Cancel the request. This must result in onFailure() being called in order to
	 * send the client a cancel FetchException, and to removeFrom() the state.
	 */
	public void cancel(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Cancelling "+this, new Exception("debug"));
		ClientGetState s;
		synchronized(this) {
			if(super.cancel()) {
				if(logMINOR) Logger.minor(this, "Already cancelled "+this);
				return;
			}
			s = currentState;
		}
		if(persistent())
			container.store(this);
		if(s != null) {
			if(persistent())
				container.activate(s, 1);
			if(logMINOR) Logger.minor(this, "Cancelling "+s+" for "+this+" instance "+super.toString());
			s.cancel(container, context);
			if(persistent())
				container.deactivate(s, 1);
		} else {
			if(logMINOR) Logger.minor(this, "Nothing to cancel");
		}
		ClientGetState state;
		synchronized(this) {
			state = currentState;
		}
		if(state == null) return;
		Logger.error(this, "Cancelling "+currentState+" did not call onFailure(), so did not removeFrom() or call callback");
		this.onFailure(new FetchException(FetchException.CANCELLED), state, container, context);
	}

	@Override
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	@Override
	public FreenetURI getURI() {
		return uri;
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized), container, context);
	}

	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized(container, context);
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		synchronized(this) {
			if(currentState == oldState) {
				currentState = newState;
				if(logMINOR) Logger.minor(this, "Transition: "+oldState+" -> "+newState+" on "+this+" persistent = "+persistent()+" instance = "+super.toString(), new Exception("debug"));
			} else {
				if(logMINOR) Logger.minor(this, "Ignoring transition: "+oldState+" -> "+newState+" because current = "+currentState+" on "+this+" persistent = "+persistent(), new Exception("debug"));
				return;
			}
		}
		if(persistent()) {
			container.store(this);
//			container.deactivate(this, 1);
//			System.gc();
//			System.runFinalization();
//			System.gc();
//			System.runFinalization();
//			container.activate(this, 1);
//			synchronized(this) {
//				Logger.minor(this, "Post transition: "+currentState);
//			}
		}
	}

	public boolean canRestart() {
		if(currentState != null && !finished) {
			if(logMINOR) Logger.minor(this, "Cannot restart because not finished for "+uri);
			return false;
		}
		return true;
	}

	public boolean restart(FreenetURI redirect, ObjectContainer container, ClientContext context) throws FetchException {
		return start(true, redirect, container, context);
	}

	@Override
	public String toString() {
		return super.toString();
	}
	
	// FIXME not persisting binary blob stuff - any stream won't survive shutdown...
	
	void addKeyToBinaryBlob(ClientKeyBlock block, ObjectContainer container, ClientContext context) {
		if(binaryBlobKeysAddedAlready == null) return;
		if(persistent()) {
			container.activate(binaryBlobStream, 1);
			container.activate(binaryBlobKeysAddedAlready, 1);
		}
		if(logMINOR) 
			Logger.minor(this, "Adding key "+block.getClientKey().getURI()+" to "+this, new Exception("debug"));
		Key key = block.getKey();
		synchronized(binaryBlobKeysAddedAlready) {
			if(binaryBlobStream == null) return;
			if(binaryBlobKeysAddedAlready.contains(key)) return;
			binaryBlobKeysAddedAlready.add(key);
			try {
				BinaryBlob.writeKey(binaryBlobStream, block, key);
			} catch (IOException e) {
				Logger.error(this, "Failed to write key to binary blob stream: "+e, e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to write key to binary blob stream: "+e), null, container, context);
				binaryBlobStream = null;
				binaryBlobKeysAddedAlready.clear();
			}
		}
	}
	
	/**
	 * Close the binary blob stream.
	 * @return True unless a failure occurred, in which case we will have already
	 * called onFailure() with an appropriate error.
	 */
	private boolean closeBinaryBlobStream(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(binaryBlobStream, 1);
			container.activate(binaryBlobKeysAddedAlready, 1);
		}
		if(binaryBlobKeysAddedAlready == null) return true;
		synchronized(binaryBlobKeysAddedAlready) {
			if(binaryBlobStream == null) return true;
			boolean triedClose = false;
			try {
				BinaryBlob.writeEndBlob(binaryBlobStream);
				binaryBlobStream.flush();
				triedClose = true;
				binaryBlobStream.close();
				return true;
			} catch (IOException e) {
				Logger.error(this, "Failed to close binary blob stream: "+e, e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to close binary blob stream: "+e), null, container, context);
				if(!triedClose) {
					try {
						binaryBlobStream.close();
					} catch (IOException e1) {
						// Ignore
					}
				}
				return false;
			} finally {
				binaryBlobStream = null;
				binaryBlobKeysAddedAlready.clear();
			}
		}
	}

	boolean collectingBinaryBlob() {
		return binaryBlobBucket != null;
	}

	public void onExpectedMIME(String mime, ObjectContainer container) {
		if(finalizedMetadata) return;
		expectedMIME = mime;
		if(persistent())
			container.store(this);
	}

	public void onExpectedSize(long size, ObjectContainer container) {
		if(finalizedMetadata) return;
		expectedSize = size;
		if(persistent())
			container.store(this);
	}

	public void onFinalizedMetadata(ObjectContainer container) {
		finalizedMetadata = true;
		if(persistent())
			container.store(this);
	}
	
	public boolean finalizedMetadata() {
		return finalizedMetadata;
	}
	
	public String expectedMIME() {
		return expectedMIME;
	}
	
	public long expectedSize() {
		return expectedSize;
	}

	public ClientCallback getClientCallback() {
		return clientCallback;
	}
	
	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.activate(uri, 5);
		uri.removeFrom(container);
		container.activate(ctx, 1);
		ctx.removeFrom(container);
		container.activate(actx, 5);
		actx.removeFrom(container);
		if(returnBucket != null) {
			container.activate(returnBucket, 1);
			returnBucket.removeFrom(container);
		}
		super.removeFrom(container, context);
	}
}
