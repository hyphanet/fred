/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetchContext;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * A high level data request.
 */
public class ClientGetter extends BaseClientGetter {

	final ClientCallback client;
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
	public ClientGetter(ClientCallback client, ClientRequestScheduler chkSched, ClientRequestScheduler sskSched,
			    FreenetURI uri, FetchContext ctx, short priorityClass, Object clientContext, Bucket returnBucket, Bucket binaryBlobBucket) {
		super(priorityClass, chkSched, sskSched, clientContext);
		this.client = client;
		this.returnBucket = returnBucket;
		this.uri = uri;
		this.ctx = ctx;
		this.finished = false;
		this.actx = new ArchiveContext(ctx.maxArchiveLevels);
		this.binaryBlobBucket = binaryBlobBucket;
		if(binaryBlobBucket != null) {
			binaryBlobKeysAddedAlready = new HashSet();
		} else
			binaryBlobKeysAddedAlready = null;
		archiveRestarts = 0;
	}

	public void start() throws FetchException {
		start(false, null);
	}

	public boolean start(boolean restart, FreenetURI overrideURI) throws FetchException {
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
				currentState = SingleFileFetcher.create(this, this, new ClientMetadata(),
						uri, ctx, actx, ctx.maxNonSplitfileRetries, 0, false, -1, true,
						returnBucket, true);
			}
			if(cancelled) cancel();
			if(currentState != null && !finished) {
				if(binaryBlobBucket != null) {
					try {
						binaryBlobStream = new DataOutputStream(binaryBlobBucket.getOutputStream());
						BinaryBlob.writeBinaryBlobHeader(binaryBlobStream);
					} catch (IOException e) {
						onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to open binary blob bucket"), null);
						return false;
					}
				}
				currentState.schedule();
			}
			if(cancelled) cancel();
		} catch (MalformedURLException e) {
			throw new FetchException(FetchException.INVALID_URI, e);
		}
		return true;
	}

	public void onSuccess(FetchResult result, ClientGetState state) {
		if(!closeBinaryBlobStream()) return;
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
				BucketTools.copy(from, to);
				from.free();
			} catch (IOException e) {
				Logger.error(this, "Error copying from "+from+" to "+to+" : "+e.toString(), e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e.toString()), state /* not strictly to blame, but we're not ako ClientGetState... */);
			}
			result = new FetchResult(result, to);
		} else {
			if(returnBucket != null && Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "client.async returned data in returnBucket");
		}
		client.onSuccess(result, this);
	}

	public void onFailure(FetchException e, ClientGetState state) {
		closeBinaryBlobStream();
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
						start();
					} catch (FetchException e1) {
						e = e1;
						continue;
					}
					return;
				}
			}
			synchronized(this) {
				finished = true;
			}
			if(e.errorCodes != null && e.errorCodes.isOneCodeOnly())
				e = new FetchException(e.errorCodes.getFirstCode(), e);
			if(e.mode == FetchException.DATA_NOT_FOUND && super.successfulBlocks > 0)
				e = new FetchException(e, FetchException.ALL_DATA_NOT_FOUND);
			Logger.minor(this, "onFailure("+e+", "+state+") on "+this+" for "+uri, e);
			client.onFailure(e, this);
			return;
		}
	}

	public void cancel() {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Cancelling "+this);
		ClientGetState s;
		synchronized(this) {
			super.cancel();
			s = currentState;
		}
		if(s != null) {
			if(logMINOR) Logger.minor(this, "Cancelling "+currentState);
			s.cancel();
		}
	}

	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	public FreenetURI getURI() {
		return uri;
	}

	public void notifyClients() {
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized));
	}

	public void onBlockSetFinished(ClientGetState state) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized();
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		synchronized(this) {
			if(currentState == oldState) {
				currentState = newState;
				Logger.minor(this, "Transition: "+oldState+" -> "+newState);
			} else
				Logger.minor(this, "Ignoring transition: "+oldState+" -> "+newState+" because current = "+currentState);
		}
		// TODO Auto-generated method stub

	}

	public boolean canRestart() {
		if(currentState != null && !finished) {
			Logger.minor(this, "Cannot restart because not finished for "+uri);
			return false;
		}
		return true;
	}

	public boolean restart(FreenetURI redirect) throws FetchException {
		return start(true, redirect);
	}

	public String toString() {
		return super.toString()+ ':' +uri;
	}
	
	void addKeyToBinaryBlob(ClientKeyBlock block) {
		if(binaryBlobKeysAddedAlready == null) return;
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
				onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to write key to binary blob stream: "+e), null);
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
	private boolean closeBinaryBlobStream() {
		if(binaryBlobBucket == null) return true;
		synchronized(binaryBlobKeysAddedAlready) {
			try {
				BinaryBlob.writeEndBlob(binaryBlobStream);
				binaryBlobStream.close();
				return true;
			} catch (IOException e) {
				Logger.error(this, "Failed to close binary blob stream: "+e, e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to close binary blob stream: "+e), null);
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
}