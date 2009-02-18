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
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * A high level data request.
 */
public class ClientGetter extends BaseClientGetter {

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
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting "+this);
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
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Succeeded from "+state+" on "+this);
		if(!closeBinaryBlobStream(container, context)) return;
		synchronized(this) {
			finished = true;
			currentState = null;
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
				if(Logger.shouldLog(Logger.MINOR, this))
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
			if(returnBucket != null && Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "client.async returned data in returnBucket");
		}
		if(persistent()) {
			container.activate(state, 1);
			state.removeFrom(container, context);
		}
		FetchResult res = result;
		if(persistent()) {
			container.store(this);
			container.activate(clientCallback, 1);
		}
		clientCallback.onSuccess(res, ClientGetter.this, container);
	}

	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Failed from "+state+" : "+e+" on "+this, e);
		closeBinaryBlobStream(container, context);
		if(persistent() && state != null) {
			container.activate(state, 1);
			state.removeFrom(container, context);
		}
		while(true) {
			if(e.mode == FetchException.ARCHIVE_RESTART) {
				int ar;
				synchronized(this) {
					archiveRestarts++;
					ar = archiveRestarts;
				}
				if(Logger.shouldLog(Logger.MINOR, this))
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
				currentState = null;
			}
			if(e.errorCodes != null && e.errorCodes.isOneCodeOnly())
				e = new FetchException(e.errorCodes.getFirstCode(), e);
			if(e.mode == FetchException.DATA_NOT_FOUND && super.successfulBlocks > 0)
				e = new FetchException(e, FetchException.ALL_DATA_NOT_FOUND);
			Logger.minor(this, "onFailure("+e+", "+state+") on "+this+" for "+uri, e);
			final FetchException e1 = e;
			if(persistent())
				container.store(this);
			clientCallback.onFailure(e1, ClientGetter.this, container);
			return;
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Cancelling "+this, new Exception("debug"));
		ClientGetState s;
		synchronized(this) {
			super.cancel();
			s = currentState;
		}
		if(persistent())
			container.store(this);
		if(s != null) {
			if(logMINOR) Logger.minor(this, "Cancelling "+currentState);
			if(persistent())
				container.activate(s, 1);
			s.cancel(container, context);
			if(persistent())
				container.deactivate(s, 1);
		} else {
			if(logMINOR) Logger.minor(this, "Nothing to cancel");
		}
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
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized(container, context);
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		synchronized(this) {
			if(currentState == oldState) {
				currentState = newState;
				Logger.minor(this, "Transition: "+oldState+" -> "+newState+" on "+this);
			} else
				Logger.minor(this, "Ignoring transition: "+oldState+" -> "+newState+" because current = "+currentState+" on "+this);
		}
		if(persistent())
			container.store(this);
	}

	public boolean canRestart() {
		if(currentState != null && !finished) {
			Logger.minor(this, "Cannot restart because not finished for "+uri);
			return false;
		}
		return true;
	}

	public boolean restart(FreenetURI redirect, ObjectContainer container, ClientContext context) throws FetchException {
		return start(true, redirect, container, context);
	}

	@Override
	public String toString() {
		return super.toString()+ ':' +uri;
	}
	
	// FIXME not persisting binary blob stuff - any stream won't survive shutdown...
	
	void addKeyToBinaryBlob(ClientKeyBlock block, ObjectContainer container, ClientContext context) {
		if(binaryBlobKeysAddedAlready == null) return;
		if(persistent()) {
			container.activate(binaryBlobStream, 1);
			container.activate(binaryBlobKeysAddedAlready, 1);
		}
		if(Logger.shouldLog(Logger.MINOR, this)) 
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
}