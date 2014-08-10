/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.List;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.async.BinaryBlobWriter.BinaryBlobAlreadyClosedException;
import freenet.client.events.EnterFiniteCooldownEvent;
import freenet.client.events.ExpectedFileSizeEvent;
import freenet.client.events.ExpectedHashesEvent;
import freenet.client.events.ExpectedMIMEEvent;
import freenet.client.events.SendingToNetworkEvent;
import freenet.client.events.SplitfileCompatibilityModeEvent;
import freenet.client.events.SplitfileProgressEvent;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.FilterMIMEType;
import freenet.client.filter.UnsafeContentTypeException;
import freenet.crypt.HashResult;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.compress.DecompressorThreadManager;
import freenet.support.io.Closer;
import freenet.support.io.InsufficientDiskSpaceException;

/**
 * A high level data request. Follows redirects, downloads splitfiles, etc. Similar to what you get from FCP,
 * and is used internally to implement FCP. Also used by fproxy, and plugins, and so on. The current state
 * of the request is stored in currentState. The ClientGetState's do most of the work. SingleFileFetcher for
 * example fetches a key, parses the metadata, and if necessary creates other states to e.g. fetch splitfiles.
 */
public class ClientGetter extends BaseClientGetter implements WantsCooldownCallback, Serializable {

    private static final long serialVersionUID = 1L;
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
	/** If not null, BucketWrapper to return a binary blob in */
	private final BinaryBlobWriter binaryBlobWriter;
	/** If true, someone else is responsible for this BlobWriter, usually its a shared one */
	private final boolean dontFinalizeBlobWriter;
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
	private HashResult[] hashes;
	private final Bucket initialMetadata;
	/** If set, and filtering is enabled, the MIME type we filter with must 
	 * be compatible with this extension. */
	final String forceCompatibleExtension;

	// Shorter constructors for convenience and backwards compatibility.

	public ClientGetter(ClientGetCallback client,
		    FreenetURI uri, FetchContext ctx, short priorityClass) {
		this(client, uri, ctx, priorityClass, null, null, null);
	}

	public ClientGetter(ClientGetCallback client,
		    FreenetURI uri, FetchContext ctx, short priorityClass, Bucket returnBucket) {
		this(client, uri, ctx, priorityClass, returnBucket, null, null);
	}

	public ClientGetter(ClientGetCallback client,
		    FreenetURI uri, FetchContext ctx, short priorityClass, Bucket returnBucket, BinaryBlobWriter binaryBlobWriter) {
		this(client, uri, ctx, priorityClass, returnBucket, binaryBlobWriter, null);
	}

	public ClientGetter(ClientGetCallback client,
		    FreenetURI uri, FetchContext ctx, short priorityClass, Bucket returnBucket, BinaryBlobWriter binaryBlobWriter, Bucket initialMetadata) {
		this(client, uri, ctx, priorityClass, returnBucket, binaryBlobWriter, false, initialMetadata);
	}

	public ClientGetter(ClientGetCallback client,
			FreenetURI uri, FetchContext ctx, short priorityClass, Bucket returnBucket, BinaryBlobWriter binaryBlobWriter, boolean dontFinalizeBlobWriter, Bucket initialMetadata) {
		this(client, uri, ctx, priorityClass, returnBucket, binaryBlobWriter, dontFinalizeBlobWriter, initialMetadata, null);
	}
	
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
	 * @param binaryBlobWriter If non-null, we will write all the keys accessed (or that could have been
	 * accessed in the case of redundant structures such as splitfiles) to this binary blob writer.
	 * @param dontFinalizeBlobWriter If true, the caller is responsible for BlobWriter finalization
	 */
	public ClientGetter(ClientGetCallback client,
			FreenetURI uri, FetchContext ctx, short priorityClass, Bucket returnBucket, BinaryBlobWriter binaryBlobWriter, boolean dontFinalizeBlobWriter, Bucket initialMetadata, String forceCompatibleExtension) {
		super(priorityClass, client);
		this.clientCallback = client;
		this.returnBucket = returnBucket;
		this.uri = uri.clone();
		this.ctx = ctx;
		this.finished = false;
		this.actx = new ArchiveContext(ctx.maxTempLength, ctx.maxArchiveLevels);
		this.binaryBlobWriter = binaryBlobWriter;
		this.dontFinalizeBlobWriter = dontFinalizeBlobWriter;
		this.initialMetadata = initialMetadata;
		archiveRestarts = 0;
		this.forceCompatibleExtension = forceCompatibleExtension;
	}
	
	protected ClientGetter() {
	    // For serialization.
	    clientCallback = null;
	    ctx = null;
	    actx = null;
	    returnBucket = null;
	    binaryBlobWriter = null;
	    dontFinalizeBlobWriter = false;
	    initialMetadata = null;
	    forceCompatibleExtension = null;
	}

	public void start(ClientContext context) throws FetchException {
		start(false, null, context);
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
	public boolean start(boolean restart, FreenetURI overrideURI, ClientContext context) throws FetchException {
		if(logMINOR)
			Logger.minor(this, "Starting "+this+" persistent="+persistent()+" for "+uri);
		try {
			// FIXME synchronization is probably unnecessary.
			// But we DEFINITELY do not want to synchronize while calling currentState.schedule(),
			// which can call onSuccess and thereby almost anything.
			HashResult[] oldHashes = null;
			String overrideMIME = ctx.overrideMIME;
			synchronized(this) {
				if(restart)
					clearCountersOnRestart();
				if(overrideURI != null) uri = overrideURI;
				if(finished) {
					if(!restart) return false;
					currentState = null;
					cancelled = false;
					finished = false;
				}
				expectedMIME = null;
				if(overrideMIME != null)
					expectedMIME = overrideMIME;
				expectedSize = 0;
				oldHashes = hashes;
				hashes = null;
				finalBlocksRequired = 0;
				finalBlocksTotal = 0;
				resetBlocks();
				currentState = SingleFileFetcher.create(this, this,
						uri, ctx, actx, ctx.maxNonSplitfileRetries, 0, false, -1, true,
						true, context, realTimeFlag, initialMetadata != null);
			}
			if(cancelled) cancel();
			// schedule() may deactivate stuff, so store it now.
			if(currentState != null && !finished) {
				if(initialMetadata != null && currentState instanceof SingleFileFetcher) {
					((SingleFileFetcher)currentState).startWithMetadata(initialMetadata, context);
				} else
					currentState.schedule(context);
			}
			if(cancelled) cancel();
		} catch (MalformedURLException e) {
			throw new FetchException(FetchException.INVALID_URI, e);
		} catch (KeyListenerConstructionException e) {
			onFailure(e.getFetchException(), currentState, context);
		}
		return true;
	}

	@Override
	protected void clearCountersOnRestart() {
		this.archiveRestarts = 0;
		this.expectedMIME = null;
		this.expectedSize = 0;
		this.finalBlocksRequired = 0;
		this.finalBlocksTotal = 0;
		super.clearCountersOnRestart();
	}

	/**
	 * Called when the request succeeds.
	 * @param state The ClientGetState which retrieved the data.
	 */
	@Override
	public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Succeeded from "+state+" on "+this);
		// Fetching the container is essentially a full success, we should update the latest known good.
		context.uskManager.checkUSK(uri, persistent(), false);
		try {
			if (binaryBlobWriter != null && !dontFinalizeBlobWriter) binaryBlobWriter.finalizeBucket();
		} catch (IOException ioe) {
			onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to close binary blob stream: "+ioe), null, context);
			return;
		} catch (BinaryBlobAlreadyClosedException e) {
			onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to close binary blob stream, already closed: "+e, e), null, context);
			return;
		}
		String mimeType = clientMetadata == null ? null : clientMetadata.getMIMEType();
		
		if(forceCompatibleExtension != null && ctx.filterData) {
		    if(mimeType == null) {
		        onFailure(new FetchException(FetchException.MIME_INCOMPATIBLE_WITH_EXTENSION, "No MIME type but need specific extension \""+forceCompatibleExtension+"\""), null, context);
		        return;
		    }
			try {
				checkCompatibleExtension(mimeType);
			} catch (FetchException e) {
				onFailure(e, null, context);
				return;
			}
		}

		synchronized(this) {
			finished = true;
			currentState = null;
			expectedMIME = mimeType;
				
		}
		// Rest of method does not need to be synchronized.
		// Variables will be updated on exit of method, and the only thing that is
		// set is the returnBucket and the result. Not locking not only prevents
		// nested locking resulting in deadlocks, it also prevents long locks due to
		// doing massive encrypted I/Os while holding a lock.

		PipedOutputStream dataOutput = new PipedOutputStream();
		PipedInputStream dataInput = new PipedInputStream();
		OutputStream output = null;

		DecompressorThreadManager decompressorManager = null;
		ClientGetWorkerThread worker = null;
		Bucket finalResult = null;
		FetchResult result = null;

        long maxLen = -1;
        synchronized(this) {
            if(expectedSize > 0) {
                maxLen = expectedSize;
            }
        }
        if(ctx.filterData && maxLen >= 0) {
            maxLen = expectedSize * 2 + 1024;
        }
        if(maxLen == -1) {
            maxLen = Math.max(ctx.maxTempLength, ctx.maxOutputLength);
        }
        
		FetchException ex = null; // set on failure
		try {
			if(returnBucket == null) finalResult = context.getBucketFactory(persistent()).makeBucket(maxLen);
			else finalResult = returnBucket;
			if(logMINOR) Logger.minor(this, "Writing final data to "+finalResult+" return bucket is "+returnBucket);
			dataOutput .connect(dataInput);
			result = new FetchResult(clientMetadata, finalResult);

			// Decompress
			if(decompressors != null) {
				if(logMINOR) Logger.minor(this, "Decompressing...");
				decompressorManager =  new DecompressorThreadManager(dataInput, decompressors, maxLen);
				dataInput = decompressorManager.execute();
			}

			output = finalResult.getOutputStream();
			if(ctx.overrideMIME != null) mimeType = ctx.overrideMIME;
			worker = new ClientGetWorkerThread(dataInput, output, uri, mimeType, hashes, ctx.filterData, ctx.charset, ctx.prefetchHook, ctx.tagReplacer, context.linkFilterExceptionProvider);
			worker.start();
			try {
				streamGenerator.writeTo(dataOutput, context);
			} catch(IOException e) {
				//Check if the worker thread caught an exception
				worker.getError();
				//If not, throw the original error
				throw e;
			}

			// An error will propagate backwards, so wait for the worker first.
			
			if(logMINOR) Logger.minor(this, "Waiting for hashing, filtration, and writing to finish");
			worker.waitFinished();

			if(decompressorManager != null) {
				if(logMINOR) Logger.minor(this, "Waiting for decompression to finalize");
				decompressorManager.waitFinished();
			}

			if(worker.getClientMetadata() != null) {
				clientMetadata = worker.getClientMetadata();
				result = new FetchResult(clientMetadata, finalResult);
			}
		} catch(UnsafeContentTypeException e) {
			Logger.normal(this, "Error filtering content: will not validate", e);
			ex = e.createFetchException(ctx.overrideMIME != null ? ctx.overrideMIME : expectedMIME, expectedSize);
			/*Not really the state's fault*/
		} catch(URISyntaxException e) {
			//Impossible
			Logger.error(this, "URISyntaxException converting a FreenetURI to a URI!: "+e, e);
			ex = new FetchException(FetchException.INTERNAL_ERROR, e);
			/*Not really the state's fault*/
		} catch(CompressionOutputSizeException e) {
			Logger.error(this, "Caught "+e, e);
			ex = new FetchException(FetchException.TOO_BIG, e);
		} catch (InsufficientDiskSpaceException e) {
		    ex = new FetchException(FetchException.NOT_ENOUGH_DISK_SPACE);
		} catch(IOException e) {
			Logger.error(this, "Caught "+e, e);
			ex = new FetchException(FetchException.BUCKET_ERROR, e);
		} catch(FetchException e) {
			Logger.error(this, "Caught "+e, e);
			ex = e;
		} catch(Throwable t) {
			Logger.error(this, "Caught "+t, t);
			ex = new FetchException(FetchException.INTERNAL_ERROR, t);
		} finally {
			Closer.close(dataInput);
			Closer.close(dataOutput);
			Closer.close(output);
		}
		if(ex != null) {
			onFailure(ex, state, context, true);
			if(finalResult != null && finalResult != returnBucket) {
				finalResult.free();
			}
			if(result != null) {
			Bucket data = result.asBucket();
			data.free();
			}
			return;
		}

			clientCallback.onSuccess(result, ClientGetter.this);
	}

	/**
	 * Called when the request fails. Retrying will have already been attempted by the calling state, if
	 * appropriate; we have tried to get the data, and given up.
	 * @param e The reason for failure, in the form of a FetchException.
	 * @param state The failing state.
	 */
	@Override
	public void onFailure(FetchException e, ClientGetState state, ClientContext context) {
		onFailure(e, state, context, false);
	}

	/**
	 * Internal version. Adds one parameter.
	 * @param force If true, finished may already have been set. This is usually
	 * set when called from onSuccess after it has set finished = true.
	 */
	public void onFailure(FetchException e, ClientGetState state, ClientContext context, boolean force) {
		if(logMINOR)
			Logger.minor(this, "Failed from "+state+" : "+e+" on "+this, e);
		ClientGetState oldState = null;
		if(expectedSize > 0 && (e.expectedSize <= 0 || finalBlocksTotal != 0))
			e.expectedSize = expectedSize;
		
		if(e.mode == FetchException.TOO_BIG && ctx.filterData) {
			// Check for MIME type issues first. Because of the filtering behaviour the user needs to see these first.
			if(e.finalizedSize()) {
				// Since the size is finalized, so must the MIME type be.
				String mime = e.getExpectedMimeType();
				if(ctx.overrideMIME != null)
					mime = ctx.overrideMIME;
				if(mime != null && !"".equals(mime)) {
					// Even if it's the default, it is set because we have the final size.
					UnsafeContentTypeException unsafe = ContentFilter.checkMIMEType(mime);
					if(unsafe != null) {
						e = unsafe.recreateFetchException(e, mime);
					}
				}
			}
		}
		
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
						start(context);
					} catch (FetchException e1) {
						e = e1;
						continue;
					}
					return;
				}
			}
			boolean alreadyFinished = false;
			synchronized(this) {
				if(finished && !force) {
					if(!cancelled)
						Logger.error(this, "Already finished - not calling callbacks on "+this, new Exception("error"));
					alreadyFinished = true;
				}
				finished = true;
				oldState = currentState;
				currentState = null;
			}
			if(!alreadyFinished) {
				try {
					if (binaryBlobWriter != null && !dontFinalizeBlobWriter) binaryBlobWriter.finalizeBucket();
				} catch (IOException ioe) {
					// the request is already failed but fblob creation failed too
					// the invalid fblob must be told, more important then an valid but incomplete fblob (ADNF for example)
					if(e.mode != FetchException.CANCELLED && !force)
						e = new FetchException(FetchException.BUCKET_ERROR, "Failed to close binary blob stream: "+ioe);
				} catch (BinaryBlobAlreadyClosedException ee) {
					if(e.mode != FetchException.BUCKET_ERROR && e.mode != FetchException.CANCELLED && !force)
						e = new FetchException(FetchException.BUCKET_ERROR, "Failed to close binary blob stream, already closed: "+ee, ee);
				}
			}
			if(e.errorCodes != null && e.errorCodes.isOneCodeOnly())
				e = new FetchException(e.errorCodes.getFirstCode());
			if(e.mode == FetchException.DATA_NOT_FOUND && super.successfulBlocks > 0)
				e = new FetchException(e, FetchException.ALL_DATA_NOT_FOUND);
			if(logMINOR) Logger.minor(this, "onFailure("+e+", "+state+") on "+this+" for "+uri, e);
			final FetchException e1 = e;
			if(!alreadyFinished)
				clientCallback.onFailure(e1, ClientGetter.this);
			return;
		}
	}

	/**
	 * Cancel the request. This must result in onFailure() being called in order to
	 * send the client a cancel FetchException, and to removeFrom() the state.
	 */
	@Override
	public void cancel(ClientContext context) {
		if(logMINOR) Logger.minor(this, "Cancelling "+this, new Exception("debug"));
		ClientGetState s;
		synchronized(this) {
			if(super.cancel()) {
				if(logMINOR) Logger.minor(this, "Already cancelled "+this);
				return;
			}
			s = currentState;
		}
		if(s != null) {
			if(logMINOR) Logger.minor(this, "Cancelling "+s+" for "+this+" instance "+super.toString());
			s.cancel(context);
		} else {
			if(logMINOR) Logger.minor(this, "Nothing to cancel");
		}
		ClientGetState state;
		synchronized(this) {
			state = currentState;
		}
		if(state == null) return;
		Logger.error(this, "Cancelling "+currentState+" did not call onFailure(), so did not removeFrom() or call callback");
		this.onFailure(new FetchException(FetchException.CANCELLED), state, context);
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
	public void notifyClients(ClientContext context) {
		int total = this.totalBlocks;
		int minSuccess = this.minSuccessBlocks;
		boolean finalized = blockSetFinalized;
		if(this.finalBlocksRequired != 0) {
			total = finalBlocksTotal;
			minSuccess = finalBlocksRequired;
			finalized = true;
		}
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(total, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, minSuccess, 0, finalized), context);
	}

	/**
	 * Notify clients that some part of the request has been sent to the network i.e. we have finished
	 * checking the datastore for at least some part of the request. Sent once only for any given request.
	 */
	@Override
	protected void innerToNetwork(ClientContext context) {
		ctx.eventProducer.produceEvent(new SendingToNetworkEvent(), context);
	}

	/**
	 * Called when no more blocks will be added to the total, and therefore we can confidently display a
	 * percentage for the overall progress. Will notify clients with a SplitfileProgressEvent.
	 */
	@Override
	public void onBlockSetFinished(ClientGetState state, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized(context);
	}

	/**
	 * Called when the current state creates a new state and we switch to that. For example, a
	 * SingleFileFetcher might switch to a SplitFileFetcher. Sometimes this will be called with oldState
	 * not equal to our currentState; this means that a subsidiary request has changed state, so we
	 * ignore it.
	 */
	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		synchronized(this) {
			if(currentState == oldState) {
				currentState = newState;
				if(logMINOR) Logger.minor(this, "Transition: "+oldState+" -> "+newState+" on "+this+" persistent = "+persistent()+" instance = "+super.toString(), new Exception("debug"));
			} else {
				if(logMINOR) Logger.minor(this, "Ignoring transition: "+oldState+" -> "+newState+" because current = "+currentState+" on "+this+" persistent = "+persistent(), new Exception("debug"));
				return;
			}
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
	public boolean restart(FreenetURI redirect, boolean filterData, ClientContext context) throws FetchException {
		ctx.filterData = filterData;
		return start(true, redirect, context);
	}

	@Override
	public String toString() {
		return super.toString();
	}

	// FIXME not persisting binary blob stuff - any stream won't survive shutdown...

	/**
	 * Add a block to the binary blob.
	 */
	protected void addKeyToBinaryBlob(ClientKeyBlock block, ClientContext context) {
		if(binaryBlobWriter == null) return;
		if(logMINOR)
			Logger.minor(this, "Adding key "+block.getClientKey().getURI()+" to "+this, new Exception("debug"));
		try {
			binaryBlobWriter.addKey(block, context);
		} catch (IOException e) {
			Logger.error(this, "Failed to write key to binary blob stream: "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to write key to binary blob stream: "+e), null, context);
		} catch (BinaryBlobAlreadyClosedException e) {
			Logger.error(this, "Failed to write key to binary blob stream (already closed??): "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, "Failed to write key to binary blob stream (already closed??): "+e), null, context);
		}
	}

	/** Are we collecting a binary blob? */
	protected boolean collectingBinaryBlob() {
		return binaryBlobWriter != null;
	}

	/** Called when we know the MIME type of the final data 
	 * @throws FetchException */
	@Override
	public void onExpectedMIME(ClientMetadata clientMetadata, ClientContext context) throws FetchException {
		if(finalizedMetadata) return;
		String mime = null;
		if(!clientMetadata.isTrivial())
			mime = clientMetadata.getMIMEType();
		if(ctx.overrideMIME != null)
			mime = ctx.overrideMIME;
		if(mime == null || mime.equals("")) return;
		if(ctx.filterData) {
			UnsafeContentTypeException e = ContentFilter.checkMIMEType(mime);
			if(e != null) {
				throw e.createFetchException(mime, expectedSize);
			}
			if(forceCompatibleExtension != null)
				checkCompatibleExtension(mime);
		}
		synchronized(this) {
			expectedMIME = mime;
		}
		ctx.eventProducer.produceEvent(new ExpectedMIMEEvent(mime), context);
	}

	private void checkCompatibleExtension(String mimeType) throws FetchException {
		FilterMIMEType type = ContentFilter.getMIMEType(mimeType);
		if(type == null)
			// Not our problem, will be picked up elsewhere.
			return;
		if(!DefaultMIMETypes.isValidExt(mimeType, forceCompatibleExtension))
			throw new FetchException(FetchException.MIME_INCOMPATIBLE_WITH_EXTENSION);
	}

	/** Called when we have some idea of the length of the final data */
	@Override
	public void onExpectedSize(long size, ClientContext context) {
		if(finalizedMetadata) return;
		if(finalBlocksRequired != 0) return;
		expectedSize = size;
		ctx.eventProducer.produceEvent(new ExpectedFileSizeEvent(size), context);
	}

	/** Called when we are fairly sure that the expected MIME and size won't change */
	@Override
	public void onFinalizedMetadata() {
		finalizedMetadata = true;
	}

	/** Are we sure the expected MIME and size won't change? */
	public boolean finalizedMetadata() {
		return finalizedMetadata;
	}

	/** @return The callback to be notified when we complete the request. */
	public ClientGetCallback getClientCallback() {
		return clientCallback;
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
	
	@Override
	public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ClientContext context) {
		if(finalBlocksRequired != 0 || finalBlocksTotal != 0) return;
		if(logMINOR) Logger.minor(this, "New format metadata has top data: original size "+size+" (compressed "+compressed+") blocks "+blocksReq+" / "+blocksTotal);
		onExpectedSize(size, context);
		this.finalBlocksRequired = this.minSuccessBlocks + blocksReq;
		this.finalBlocksTotal = this.totalBlocks + blocksTotal;
		notifyClients(context);
	}

	@Override
	public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] customSplitfileKey, boolean dontCompress, boolean bottomLayer, boolean definitiveAnyway, ClientContext context) {
		ctx.eventProducer.produceEvent(new SplitfileCompatibilityModeEvent(min, max, customSplitfileKey, dontCompress, bottomLayer || definitiveAnyway), context);
	}

	@Override
	public void onHashes(HashResult[] hashes, ClientContext context) {
		synchronized(this) {
			if(this.hashes != null) {
				if(!HashResult.strictEquals(hashes, this.hashes))
					Logger.error(this, "Two sets of hashes?!");
				return;
			}
			this.hashes = hashes;
		}
		HashResult[] clientHashes = hashes;
		if(persistent()) clientHashes = HashResult.copy(hashes);
		ctx.eventProducer.produceEvent(new ExpectedHashesEvent(clientHashes), context);
	}

	@Override
	public void enterCooldown(long wakeupTime, ClientContext context) {
		if(wakeupTime == Long.MAX_VALUE) {
			// Ignore.
			// FIXME implement when implement clearCooldown().
			// It means everything that can be started has been started.
		} else {
			ctx.eventProducer.produceEvent(new EnterFiniteCooldownEvent(wakeupTime), context);
		}
	}

	@Override
	public void clearCooldown() {
		// Ignore for now. FIXME.
	}

	public Bucket getBlobBucket() {
		return binaryBlobWriter.getFinalBucket();
	}
	
    public byte[] getClientDetail() throws IOException {
        if(clientCallback instanceof PersistentClientCallback) {
            return getClientDetail((PersistentClientCallback)clientCallback);
        } else
            return new byte[0];
    }
    
    /** Called for a persistent request after startup. */
    @Override
    public void innerOnResume(ClientContext context) {
        super.innerOnResume(context);
        if(currentState != null)
            try {
                currentState.onResume(context);
            } catch (FetchException e) {
                currentState = null;
                this.onFailure(e, null, context);
                return;
            }
        if(returnBucket != null)
            returnBucket.onResume(context);
        if(expectedMIME != null)
            ctx.eventProducer.produceEvent(new ExpectedMIMEEvent(expectedMIME), context);
        if(expectedSize > 0)
            ctx.eventProducer.produceEvent(new ExpectedFileSizeEvent(expectedSize), context);
        notifyClients(context);
    }

    @Override
    protected ClientBaseCallback getCallback() {
        return clientCallback;
    }

}
