/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.events.ExpectedFileSizeEvent;
import freenet.client.events.ExpectedMIMEEvent;
import freenet.client.events.SendingToNetworkEvent;
import freenet.client.events.SplitfileCompatibilityModeEvent;
import freenet.client.events.SplitfileProgressEvent;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.KnownUnsafeContentTypeException;
import freenet.client.filter.MIMEType;
import freenet.client.filter.UnknownContentTypeException;
import freenet.client.filter.UnsafeContentTypeException;
import freenet.client.filter.ContentFilter.FilterStatus;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;

/**
 * A high level data request. Follows redirects, downloads splitfiles, etc. Similar to what you get from FCP,
 * and is used internally to implement FCP. Also used by fproxy, and plugins, and so on. The current state
 * of the request is stored in currentState. The ClientGetState's do most of the work. SingleFileFetcher for
 * example fetches a key, parses the metadata, and if necessary creates other states to e.g. fetch splitfiles.
 */
public class ClientGetter extends BaseClientGetter {

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(ClientGetter.class);
	}

	/** Will be called when the request completes */
	final ClientGetCallback clientCallback;
	/** The initial Freenet URI being fetched. */
	FreenetURI uri;
	/** Settings for the fetch - max size etc */
	final FetchContext ctx;
	/** Checks for container loops. */
	final ArchiveContext actx;
	/** The current state of the request. SingleFileFetcher when processing metadata for fetching a simple
	 * key, SplitFileFetcher when fetching a splitfile, etc. */
	private ClientGetState currentState;
	/** Has the request finished? */
	private boolean finished;
	/** Number of times the fetch has been restarted because a container was out of date */
	private int archiveRestarts;
	/** If not null, Bucket to return the data in, otherwise we create one. */
	final Bucket returnBucket;
	/** If not null, Bucket to return a binary blob in */
	final Bucket binaryBlobBucket;
	/** If not null, HashSet to track keys already added for a binary blob */
	final HashSet<Key> binaryBlobKeysAddedAlready;
	/** We are writing the binary blob to this stream. FIXME binary blobs are not persistent because of this! */
	private DataOutputStream binaryBlobStream;
	/** The expected MIME type, if we know it. Should not change. */
	private String expectedMIME;
	/** The expected size of the file, if we know it. */
	private long expectedSize;
	/** If true, the metadata (mostly the expected size) shouldn't change further. */
	private boolean finalizedMetadata;
	/** Callback to spy on the metadata at each stage of the request */
	private SnoopMetadata snoopMeta;
	/** Callback to spy on the data at each stage of the request */
	private SnoopBucket snoopBucket;

	/**
	 * Fetch a key.
	 * @param client The callback we will call when it is completed.
	 * @param uri The URI to fetch.
	 * @param ctx The config settings for the fetch.
	 * @param priorityClass The priority at which to schedule the request.
	 * @param clientContext The context object. Used for round-robin query balancing, also indicates whether
	 * the request is persistent.
	 * @param returnBucket The bucket to return the data in. Can be null. If not null, the ClientGetter must either
	 * write the data directly to the bucket, or copy it and free the original temporary bucket. Preferably the
	 * former, obviously!
	 * @param binaryBlobBucket If non-null, we will write all the keys accessed (or that could have been
	 * accessed in the case of redundant structures such as splitfiles) in the binary blob format to this bucket.
	 */
	public ClientGetter(ClientGetCallback client,
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
			binaryBlobKeysAddedAlready = new HashSet<Key>();
		} else
			binaryBlobKeysAddedAlready = null;
		archiveRestarts = 0;
	}

	public void start(ObjectContainer container, ClientContext context) throws FetchException {
		start(false, null, container, context);
	}

	/** Start the request.
	 * @param restart If true, restart a finished request.
	 * @param overrideURI If non-null, change the URI we are fetching (usually when restarting).
	 * @param container The database, null if this is a non-persistent request; if this is a persistent
	 * request, we must be on the database thread, and we will pass the database handle around as needed.
	 * @param context The client context, contains important mostly non-persistent global objects.
	 * @return True if we restarted, false if we didn't (but only in a few cases).
	 * @throws FetchException If we were unable to restart.
	 */
	public boolean start(boolean restart, FreenetURI overrideURI, ObjectContainer container, ClientContext context) throws FetchException {
		if(persistent()) {
			container.activate(uri, 5);
			container.activate(ctx, 1);
		}
		boolean filtering = ctx.filterData;
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
						filtering ? null : returnBucket, true, container, context);
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
						throw new FetchException(FetchException.BUCKET_ERROR, "Failed to open binary blob bucket", e);
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

	/**
	 * Called when the request succeeds.
	 * @param result The final data.
	 * @param state The ClientGetState which retrieved the data.
	 */
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
		
		//Filter the data, if we are supposed to
		if(ctx.filterData){
			if(logMINOR) Logger.minor(this, "Running content filter... Prefetch hook: "+ctx.prefetchHook+" tagReplacer: "+ctx.tagReplacer);
			InputStream input = null;
			OutputStream output = null;
			try {
				String mimeType = ctx.overrideMIME != null ? ctx.overrideMIME: expectedMIME;
				if(mimeType.compareTo("application/xhtml+xml") == 0) mimeType = "text/html";
				assert(result.asBucket() != returnBucket);
				Bucket filteredResult;
				if(returnBucket == null) filteredResult = context.getBucketFactory(persistent()).makeBucket(-1);
				else {
					if(persistent()) container.activate(returnBucket, 5);
					filteredResult = returnBucket;
				}
				input = result.asBucket().getInputStream();
				output = filteredResult.getOutputStream();
				FilterStatus filterStatus = ContentFilter.filter(input, output, mimeType, uri.toURI("/"), ctx.prefetchHook, ctx.tagReplacer, ctx.charset);
				input.close();
				output.close();
				String detectedMIMEType = filterStatus.mimeType.concat(filterStatus.charset == null ? "" : "; charset="+filterStatus.charset);
				result.asBucket().free();
				result = new FetchResult(new ClientMetadata(detectedMIMEType), filteredResult);
			} catch (UnsafeContentTypeException e) {
				Logger.error(this, "Error filtering content: will not validate", e);
				onFailure(new FetchException(e.getFetchErrorCode(), expectedSize, e.getMessage(), e, ctx.overrideMIME != null ? ctx.overrideMIME : expectedMIME), state/*Not really the state's fault*/, container, context);
				return;
			} catch (URISyntaxException e) {
				// Impossible
				Logger.error(this, "URISyntaxException converting a FreenetURI to a URI!: "+e, e);
				onFailure(new FetchException(FetchException.INTERNAL_ERROR, e), state/*Not really the state's fault*/, container, context);
				return;
			} catch (IOException e) {
				Logger.error(this, "Error filtering content", e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e), state/*Not really the state's fault*/, container, context);
				return;
			} finally {
				Closer.close(input);
				Closer.close(output);
			}
		}
		else {
			if(logMINOR) Logger.minor(this, "Ignoring content filter.");
		}
		if(returnBucket == null) if(logMINOR) Logger.minor(this, "Returnbucket is null");
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
		clientCallback.onSuccess(result, ClientGetter.this, container);
	}

	/**
	 * Called when the request fails. Retrying will have already been attempted by the calling state, if
	 * appropriate; we have tried to get the data, and given up.
	 * @param e The reason for failure, in the form of a FetchException.
	 * @param state The failing state.
	 */
	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Failed from "+state+" : "+e+" on "+this, e);
		closeBinaryBlobStream(container, context);
		if(persistent())
			container.activate(uri, 5);
		ClientGetState oldState = null;
		if(expectedSize > 0 && (e.expectedSize <= 0 || finalBlocksTotal != 0))
			e.expectedSize = expectedSize;
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
	@Override
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

	/** Has the fetch completed? */
	@Override
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	/** What was the URI we were fetching? */
	@Override
	public FreenetURI getURI() {
		return uri;
	}

	/**
	 * Notify clients listening to our ClientEventProducer of the current progress, in the form of a
	 * SplitfileProgressEvent.
	 */
	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		int total = this.totalBlocks;
		int minSuccess = this.minSuccessBlocks;
		boolean finalized = blockSetFinalized;
		if(this.finalBlocksRequired != 0) {
			total = finalBlocksTotal;
			minSuccess = finalBlocksRequired;
			finalized = true;
		}
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(total, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, minSuccess, finalized), container, context);
	}

	/**
	 * Notify clients that some part of the request has been sent to the network i.e. we have finished
	 * checking the datastore for at least some part of the request. Sent once only for any given request.
	 */
	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new SendingToNetworkEvent(), container, context);
	}

	/**
	 * Called when no more blocks will be added to the total, and therefore we can confidently display a
	 * percentage for the overall progress. Will notify clients with a SplitfileProgressEvent.
	 */
	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized(container, context);
	}

	/**
	 * Called when the current state creates a new state and we switch to that. For example, a
	 * SingleFileFetcher might switch to a SplitFileFetcher. Sometimes this will be called with oldState
	 * not equal to our currentState; this means that a subsidiary request has changed state, so we
	 * ignore it.
	 */
	@Override
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

	/**
	 * Can the request be restarted?
	 */
	public boolean canRestart() {
		if(currentState != null && !finished) {
			if(logMINOR) Logger.minor(this, "Cannot restart because not finished for "+uri);
			return false;
		}
		return true;
	}

	/**
	 * Restart the request.
	 * @param redirect Use this URI instead of the old one.
	 * @param container The database. We must be on the database thread! See ClientContext for convenience
	 * methods.
	 * @return True if we successfully restarted, false if we can't restart.
	 * @throws FetchException If something went wrong.
	 */
	public boolean restart(FreenetURI redirect, ObjectContainer container, ClientContext context) throws FetchException {
		return start(true, redirect, container, context);
	}

	@Override
	public String toString() {
		return super.toString();
	}

	// FIXME not persisting binary blob stuff - any stream won't survive shutdown...

	/**
	 * Add a block to the binary blob.
	 */
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

	/** Are we collecting a binary blob? */
	boolean collectingBinaryBlob() {
		return binaryBlobBucket != null;
	}

	/** Called when we know the MIME type of the final data 
	 * @throws FetchException */
	public void onExpectedMIME(String mime, ObjectContainer container, ClientContext context) throws FetchException {
		if(finalizedMetadata) return;
		if(persistent()) {
			container.activate(ctx, 1);
		}
		expectedMIME = ctx.overrideMIME == null ? mime : ctx.overrideMIME;
		if(!(expectedMIME == null || expectedMIME.equals("") || expectedMIME.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))) {
			MIMEType handler = ContentFilter.getMIMEType(expectedMIME);
			if((handler == null || (handler.readFilter == null && !handler.safeToRead)) && ctx.filterData) {
				UnsafeContentTypeException e;
				if(handler == null) {
					if(logMINOR) Logger.minor(this, "Unable to get filter handler for MIME type "+expectedMIME);
					e = new UnknownContentTypeException(expectedMIME);
				}
				else {
					if(logMINOR) Logger.minor(this, "Unable to filter unsafe MIME type "+expectedMIME);
					e = new KnownUnsafeContentTypeException(handler);
				}
				throw new FetchException(e.getFetchErrorCode(), expectedSize, e.getMessage(), e, expectedMIME);
			}
		}
		if(persistent()) {
			container.store(this);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new ExpectedMIMEEvent(mime), container, context);

	}

	/** Called when we have some idea of the length of the final data */
	public void onExpectedSize(long size, ObjectContainer container, ClientContext context) {
		if(finalizedMetadata) return;
		if(finalBlocksRequired != 0) return;
		expectedSize = size;
		if(persistent()) {
			container.store(this);
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new ExpectedFileSizeEvent(size), container, context);
	}

	/** Called when we are fairly sure that the expected MIME and size won't change */
	public void onFinalizedMetadata(ObjectContainer container) {
		finalizedMetadata = true;
		if(persistent())
			container.store(this);
	}

	/** Are we sure the expected MIME and size won't change? */
	public boolean finalizedMetadata() {
		return finalizedMetadata;
	}

	/** @return The expected MIME type, if we know it. */
	public String expectedMIME() {
		return expectedMIME;
	}

	/** @return The expected size of the returned data, if we know it. Could change. */
	public long expectedSize() {
		return expectedSize;
	}

	/** @return The callback to be notified when we complete the request. */
	public ClientGetCallback getClientCallback() {
		return clientCallback;
	}

	/** Remove the ClientGetter from the database. You must call this on the database thread, and it must
	 * be a persistent request. We do not remove anything we are not responsible for. */
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

	/** Get the metadata snoop callback */
	public SnoopMetadata getMetaSnoop() {
		return snoopMeta;
	}

	/** Set a callback to snoop on metadata during fetches. Call this before
	 * starting the request. */
	public SnoopMetadata setMetaSnoop(SnoopMetadata newSnoop) {
		SnoopMetadata old = snoopMeta;
		snoopMeta = newSnoop;
		return old;
	}

	/** Get the intermediate data snoop callback */
	public SnoopBucket getBucketSnoop() {
		return snoopBucket;
	}

	/** Set a callback to snoop on buckets (all intermediary data - metadata, containers) during fetches.
	 * Call this before starting the request. */
	public SnoopBucket setBucketSnoop(SnoopBucket newSnoop) {
		SnoopBucket old = snoopBucket;
		snoopBucket = newSnoop;
		return old;
	}

	private int finalBlocksRequired;
	private int finalBlocksTotal;
	
	public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ObjectContainer container, ClientContext context) {
		if(finalBlocksRequired != 0 || finalBlocksTotal != 0) return;
		System.out.println("New format metadata has top data: original size "+size+" (compressed "+compressed+") blocks "+blocksReq+" / "+blocksTotal);
		onExpectedSize(size, container, context);
		this.finalBlocksRequired = this.minSuccessBlocks + blocksReq;
		this.finalBlocksTotal = this.totalBlocks + blocksTotal;
		if(persistent()) container.store(this);
		notifyClients(container, context);
	}

	public void onSplitfileCompatibilityMode(long min, long max, ObjectContainer container, ClientContext context) {
		ctx.eventProducer.produceEvent(new SplitfileCompatibilityModeEvent(min, max), container, context);
	}
}
