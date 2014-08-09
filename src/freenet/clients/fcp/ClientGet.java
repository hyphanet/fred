/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertContext;
import freenet.client.async.BinaryBlob;
import freenet.client.async.BinaryBlobWriter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.async.PersistentClientCallback;
import freenet.client.async.PersistentJob;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.EnterFiniteCooldownEvent;
import freenet.client.events.ExpectedFileSizeEvent;
import freenet.client.events.ExpectedHashesEvent;
import freenet.client.events.ExpectedMIMEEvent;
import freenet.client.events.SendingToNetworkEvent;
import freenet.client.events.SplitfileCompatibilityModeEvent;
import freenet.client.events.SplitfileProgressEvent;
import freenet.clients.fcp.RequestIdentifier.RequestType;
import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;
import freenet.support.io.NullBucket;

/**
 * A simple client fetch. This can of course fetch arbitrarily large
 * files, including splitfiles, redirects, etc.
 */
public class ClientGet extends ClientRequest implements ClientGetCallback, ClientEventListener, PersistentClientCallback {

    private static final long serialVersionUID = 1L;
    /** Fetch context. Never passed in: always created new by the ClientGet. Therefore, we
	 * can safely delete it in requestWasRemoved(). */
	private final FetchContext fctx;
	private final ClientGetter getter;
	private final short returnType;
	private final File targetFile;
	private final File tempFile;
	/** True only if returnType is RETURN_TYPE_DISK and we were unable to rename from the temp file 
	 * to to final file. */
	private boolean returningTempFile;
	/** Bucket passed in to the ClientGetter to return data in. Null unless returntype=disk */
	private Bucket returnBucket;
	private final boolean binaryBlob;

	// Verbosity bitmasks
	private static final int VERBOSITY_SPLITFILE_PROGRESS = 1;
	private static final int VERBOSITY_SENT_TO_NETWORK = 2;
	private static final int VERBOSITY_COMPATIBILITY_MODE = 4;
	private static final int VERBOSITY_EXPECTED_HASHES = 8;
	private static final int VERBOSITY_EXPECTED_TYPE = 32;
	private static final int VERBOSITY_EXPECTED_SIZE = 64;
	private static final int VERBOSITY_ENTER_FINITE_COOLDOWN = 128;

	// Stuff waiting for reconnection
	/** Did the request succeed? Valid if finished. */
	private boolean succeeded;
	/** Length of the found data */
	private long foundDataLength = -1;
	/** MIME type of the found data */
	private String foundDataMimeType;
	/** Details of request failure */
	private GetFailedMessage getFailedMessage;
	/** Last progress message. Not persistent - FIXME this will be made persistent
	 * when we have proper persistence at the ClientGetter level. */
	private transient SimpleProgressMessage progressPending;
	/** Have we received a SendingToNetworkEvent? */
	private boolean sentToNetwork;
	private transient CompatibilityMode compatMessage;
	private transient ExpectedHashes expectedHashes;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * Create one for a global-queued request not made by FCP.
	 * @throws IdentifierCollisionException
	 * @throws NotAllowedException
	 * @throws IOException
	 */
	public ClientGet(FCPClient globalClient, FreenetURI uri, boolean dsOnly, boolean ignoreDS,
			boolean filterData, int maxSplitfileRetries, int maxNonSplitfileRetries,
			long maxOutputLength, short returnType, boolean persistRebootOnly, String identifier, int verbosity,
			short prioClass, File returnFilename, File returnTempFilename, String charset, boolean writeToClientCache, boolean realTimeFlag, FCPServer server) throws IdentifierCollisionException, NotAllowedException, IOException {
		super(uri, identifier, verbosity, charset, null, globalClient,
				prioClass,
				(persistRebootOnly ? ClientRequest.PERSIST_REBOOT : ClientRequest.PERSIST_FOREVER), realTimeFlag, null, true);

		fctx = new FetchContext(server.defaultFetchContext, FetchContext.IDENTICAL_MASK);
		fctx.eventProducer.addEventListener(this);
		fctx.localRequestOnly = dsOnly;
		fctx.ignoreStore = ignoreDS;
		fctx.maxNonSplitfileRetries = maxNonSplitfileRetries;
		fctx.maxSplitfileBlockRetries = maxSplitfileRetries;
		fctx.filterData = filterData;
		fctx.maxOutputLength = maxOutputLength;
		fctx.maxTempLength = maxOutputLength;
		fctx.canWriteClientCache = writeToClientCache;
		// FIXME fctx.ignoreUSKDatehints = ignoreUSKDatehints;
		Bucket ret = null;
		this.returnType = returnType;
		binaryBlob = false;
		String extensionCheck = null;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			this.targetFile = returnFilename;
			this.tempFile = returnTempFilename;
			if(!(server.core.allowDownloadTo(returnTempFilename) && server.core.allowDownloadTo(returnFilename)))
				throw new NotAllowedException();
			ret = new FileBucket(returnTempFilename, false, true, false, false, false);
			if(filterData) {
				String name = returnFilename.getName();
				int idx = name.lastIndexOf('.');
				if(idx != -1) {
					idx++;
					if(idx != name.length())
						extensionCheck = name.substring(idx);
				}
			}
		} else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
			targetFile = null;
			tempFile = null;
			ret = new NullBucket();
		} else {
			targetFile = null;
			tempFile = null;
				if(persistenceType == PERSIST_FOREVER)
					ret = server.core.persistentTempBucketFactory.makeBucket(maxOutputLength);
				else
					ret = server.core.tempBucketFactory.makeBucket(maxOutputLength);
		}
		returnBucket = ret;
			getter = new ClientGetter(this, uri, fctx, priorityClass,
					returnBucket, null, false, null, extensionCheck);
	}

	public ClientGet(FCPConnectionHandler handler, ClientGetMessage message, FCPServer server) throws IdentifierCollisionException, MessageInvalidException {
		super(message.uri, message.identifier, message.verbosity, message.charset, handler,
				message.priorityClass, message.persistenceType, message.realTimeFlag, message.clientToken, message.global);
		// Create a Fetcher directly in order to get more fine-grained control,
		// since the client may override a few context elements.
		fctx = new FetchContext(server.defaultFetchContext, FetchContext.IDENTICAL_MASK);
		fctx.eventProducer.addEventListener(this);
		// ignoreDS
		fctx.localRequestOnly = message.dsOnly;
		fctx.ignoreStore = message.ignoreDS;
		fctx.maxNonSplitfileRetries = message.maxRetries;
		fctx.maxSplitfileBlockRetries = message.maxRetries;
		// FIXME do something with verbosity !!
		// Has already been checked
		fctx.maxOutputLength = message.maxSize;
		fctx.maxTempLength = message.maxTempSize;
		fctx.canWriteClientCache = message.writeToClientCache;
		fctx.filterData = message.filterData;
		fctx.ignoreUSKDatehints = message.ignoreUSKDatehints;

		if(message.allowedMIMETypes != null) {
			fctx.allowedMIMETypes = new HashSet<String>();
			for(String mime : message.allowedMIMETypes)
				fctx.allowedMIMETypes.add(mime);
		}

		this.returnType = message.returnType;
		this.binaryBlob = message.binaryBlob;
		Bucket ret = null;
		String extensionCheck = null;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			this.targetFile = message.diskFile;
			this.tempFile = message.tempFile;
			if(!(server.core.allowDownloadTo(tempFile) && server.core.allowDownloadTo(targetFile)))
				throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Not allowed to download to "+tempFile+" or "+targetFile, identifier, global);
			else if(!(handler.allowDDAFrom(tempFile, true) && handler.allowDDAFrom(targetFile, true)))
				throw new MessageInvalidException(ProtocolErrorMessage.DIRECT_DISK_ACCESS_DENIED, "Not allowed to download to "+tempFile+" or "+targetFile + ". You might need to do a " + TestDDARequestMessage.NAME + " first.", identifier, global);
			ret = new FileBucket(message.tempFile, false, true, false, false, false);
			if(fctx.filterData) {
				String name = targetFile.getName();
				int idx = name.lastIndexOf('.');
				if(idx != -1) {
					idx++;
					if(idx != name.length())
						extensionCheck = name.substring(idx);
				}
			}
		} else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
			targetFile = null;
			tempFile = null;
			ret = new NullBucket();
		} else {
			targetFile = null;
			tempFile = null;
			try {
				if(persistenceType == PERSIST_FOREVER)
					ret = server.core.persistentTempBucketFactory.makeBucket(fctx.maxOutputLength);
				else
					ret = server.core.tempBucketFactory.makeBucket(fctx.maxOutputLength);
			} catch (IOException e) {
				Logger.error(this, "Cannot create bucket for temp storage: "+e, e);
				getter = null;
				returnBucket = null;
				// This is *not* a FetchException since we don't register it: it's a protocol error.
				throw new MessageInvalidException(ProtocolErrorMessage.INTERNAL_ERROR, "Cannot create bucket for temporary storage (out of disk space???): "+e, identifier, global);
			}
		}
		if(ret == null)
			Logger.error(this, "Impossible: ret = null in FCP constructor for "+this, new Exception("debug"));
		returnBucket = ret;
			getter = new ClientGetter(this,
					uri, fctx, priorityClass,
					binaryBlob ? new NullBucket() : returnBucket, binaryBlob ? new BinaryBlobWriter(returnBucket) : null, false, message.getInitialMetadata(), extensionCheck);
	}
	
	protected ClientGet() {
	    // For serialization.
	    fctx = null;
	    getter = null;
	    returnType = 0;
	    targetFile = null;
	    tempFile = null;
	    returnBucket = null;
	    binaryBlob = false;
	}

	/**
	 * Must be called just after construction, but within a transaction.
	 * @throws IdentifierCollisionException If the identifier is already in use.
	 */
	@Override
	void register(boolean noTags) throws IdentifierCollisionException {
		if(client != null)
			assert(this.persistenceType == client.persistenceType);
		if(persistenceType != PERSIST_CONNECTION)
			try {
				client.register(this);
			} catch (IdentifierCollisionException e) {
				returnBucket.free();
				throw e;
			}
			if(persistenceType != PERSIST_CONNECTION && !noTags) {
				FCPMessage msg = persistentTagMessage();
				client.queueClientRequestMessage(msg, 0);
			}
	}

	@Override
	public void start(ClientContext context) {
		try {
			synchronized(this) {
				if(finished) return;
			}
			getter.start(context);
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage();
				client.queueClientRequestMessage(msg, 0);
			}
			synchronized(this) {
				started = true;
			}
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.updateStarted(identifier, true);
				}
			}
		} catch (FetchException e) {
			synchronized(this) {
				started = true;
			} // before the failure handler
			onFailure(e, null);
		} catch (Throwable t) {
			synchronized(this) {
				started = true;
			}
			onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), null);
		}
	}

	@Override
	public void onLostConnection(ClientContext context) {
		if(persistenceType == PERSIST_CONNECTION)
			cancel(context);
		// Otherwise ignore
	}

	@Override
	public void onSuccess(FetchResult result, ClientGetter state) {
		Logger.minor(this, "Succeeded: "+identifier);
		Bucket data = result.asBucket();
		// FIXME: Fortify thinks this is double-checked locking. Technically it is, but 
		// since returnBucket is only set to non-null in this method and the constructor is it safe.
		assert(returnBucket == data || binaryBlob);
		// FIXME I don't think this is a problem in this case...? (Disk write while locked..)
		synchronized(this) {
			if(succeeded) {
				Logger.error(this, "onSuccess called twice for "+this+" ("+identifier+ ')');
				return; // We might be called twice; ignore it if so.
			}
			started = true;
			if(!binaryBlob)
				this.foundDataMimeType = result.getMimeType();
			else
				this.foundDataMimeType = BinaryBlob.MIME_TYPE;

			// completionTime is set here rather than in finish() for two reasons:
			// 1. It must be set inside the lock.
			// 2. It must be set before AllData is sent so it is consistent.
			if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT) {
				// Set it before we create the AllDataMessage.
				completionTime = System.currentTimeMillis();
				// Send all the data at once
				// FIXME there should be other options
				/*
				 * } else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
				// Do nothing
				 */
			} else if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
				// Write to temp file, then rename over filename
				if(!FileUtil.renameTo(tempFile, targetFile)) {
				    this.returningTempFile = true;
					// Don't delete temp file, user might want it.
	                returnBucket = new FileBucket(targetFile, false, true, false, false, false);
				} // else returnBucket is already tempFile.
                // Wait until after the potentially expensive rename.
                completionTime = System.currentTimeMillis();
			} else {
				// Needs to be set for all other cases too.
				completionTime = System.currentTimeMillis();
			}
			progressPending = null;
			this.foundDataLength = returnBucket.size();
			this.succeeded = true;
			finished = true;
		}
		trySendDataFoundOrGetFailed(null);
		trySendAllDataMessage(null);
		finish();
		if(client != null)
			client.notifySuccess(this);
	}

	private void trySendDataFoundOrGetFailed(FCPConnectionOutputHandler handler) {
		FCPMessage msg;

		// Don't need to lock. succeeded is only ever set, never unset.
		// and succeeded and getFailedMessage are both atomic.
		if(succeeded) {
			// FIXME: Duplicate of AllDataMessage
			// FIXME: CompletionTime is set on finish() : we need to give it current time here
			msg = new DataFoundMessage(foundDataLength, foundDataMimeType, identifier, global, startupTime, completionTime != 0 ? completionTime : System.currentTimeMillis());
		} else {
			msg = getFailedMessage;
		}

		if(handler == null && persistenceType == PERSIST_CONNECTION)
			handler = origHandler.outputHandler;
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, 0);
		if(returningTempFile) {
            FCPMessage postFetchProtocolErrorMessage = 
                new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_RENAME_FILE, false, null, identifier, global);
			if(handler != null)
				handler.queue(postFetchProtocolErrorMessage);
			else {
				client.queueClientRequestMessage(postFetchProtocolErrorMessage, 0);
			}
		}

	}
	
	private synchronized AllDataMessage getAllDataMessage() {
	    if(returnType != ClientGetMessage.RETURN_TYPE_DIRECT)
	        return null;
	    AllDataMessage msg = new AllDataMessage(returnBucket, identifier, global, startupTime, 
	            completionTime, foundDataMimeType);
        if(persistenceType == PERSIST_CONNECTION)
            msg.setFreeOnSent();
        return msg;
	}

	private void trySendAllDataMessage(FCPConnectionOutputHandler handler) {
	    if(persistenceType == PERSIST_CONNECTION) {
	        if(handler == null)
	            handler = origHandler.outputHandler;
	        handler.queue(getAllDataMessage());
	    }
	}

	private void trySendProgress(FCPMessage msg, final int verbosityMask, FCPConnectionOutputHandler handler) {
		if(msg instanceof SimpleProgressMessage) {
			progressPending = (SimpleProgressMessage)msg;
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.updateStatus(identifier, (progressPending).getEvent());
				}
			}
		} else if(msg instanceof SendingToNetworkMessage) {
			sentToNetwork = true;
		} else if(msg instanceof CompatibilityMode) {
			CompatibilityMode compat = (CompatibilityMode)msg;
			if(compatMessage != null) {
				compatMessage.merge(compat.min, compat.max, compat.cryptoKey, compat.dontCompress, compat.definitive);
			} else {
				compatMessage = compat;
			}
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.updateDetectedCompatModes(identifier, compat.getModes(), compat.cryptoKey, compat.dontCompress);
				}
			}
		} else if(msg instanceof ExpectedHashes) {
			if(expectedHashes != null) {
				Logger.error(this, "Got a new ExpectedHashes", new Exception("debug"));
			} else {
				this.expectedHashes = (ExpectedHashes)msg;
			}
		} else if(msg instanceof ExpectedMIME) {
			foundDataMimeType = ((ExpectedMIME) msg).expectedMIME;
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.updateExpectedMIME(identifier, foundDataMimeType);
				}
			}
		} else if(msg instanceof ExpectedDataLength) {
			foundDataLength = ((ExpectedDataLength) msg).dataLength;
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.updateExpectedDataLength(identifier, foundDataLength);
				}
			}
		} else if(msg instanceof EnterFiniteCooldown) {
			// Do nothing, it's not persistent.
		} else
			assert(false);
		if(persistenceType == PERSIST_CONNECTION && handler == null)
			handler = origHandler.outputHandler;
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, verbosityMask);
	}

	@Override
	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData) {
		if(!onlyData) {
			if(includePersistentRequest) {
				FCPMessage msg = persistentTagMessage();
				handler.queue(msg);
			}
			if(progressPending != null) {
				handler.queue(progressPending);
			}
			if(sentToNetwork)
				handler.queue(new SendingToNetworkMessage(identifier, global));
			if(finished)
				trySendDataFoundOrGetFailed(handler);
		} else if(returnType != ClientGetMessage.RETURN_TYPE_DIRECT) {
		    ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.WRONG_RETURN_TYPE, false, "No AllData", identifier, global);
		    handler.queue(msg);
		    return;
		}

		if(includeData) {
		    trySendAllDataMessage(handler);
		}
		
		if(compatMessage != null) {
			handler.queue(compatMessage);
		}
		
		if(expectedHashes != null) {
			handler.queue(expectedHashes);
		}

		if (foundDataMimeType != null) {
			handler.queue(new ExpectedMIME(identifier, global, foundDataMimeType));
		}
		if (foundDataLength > 0) {
			handler.queue(new ExpectedDataLength(identifier, global, foundDataLength));
		}
	}

	@Override
	protected FCPMessage persistentTagMessage() {
		return new PersistentGet(identifier, uri, verbosity, priorityClass, returnType, persistenceType, targetFile, tempFile, clientToken, client.isGlobalQueue, started, fctx.maxNonSplitfileRetries, binaryBlob, fctx.maxOutputLength, isRealTime());
	}
	
	// FIXME code duplication: ClientGet ClientPut ClientPutDir
	// FIXME maybe move to ClientRequest as final protected?
	private boolean isRealTime() {
		// FIXME: remove debug code
		if (lowLevelClient == null) {
			// This can happen but only due to data corruption - old databases on which various bugs have resulted in it getting deleted, and also possibly failed deletions.
			Logger.error(this, "lowLevelClient == null", new Exception("error"));
			return false;
		}
		return lowLevelClient.realTimeFlag();
	}

	@Override
	public void onFailure(FetchException e, ClientGetter state) {
		if(finished) return;
		synchronized(this) {
			succeeded = false;
			getFailedMessage = new GetFailedMessage(e, identifier, global);
			finished = true;
			started = true;
			completionTime = System.currentTimeMillis();
		}
		if(logMINOR)
			Logger.minor(this, "Caught "+e, e);
		trySendDataFoundOrGetFailed(null);
		// We do not want the data to be removed on failure, because the request
		// may be restarted, and the bucket persists on the getter, even if we get rid of it here.
		//freeData(container);
		finish();
		if(client != null)
			client.notifyFailure(this);
	}

	@Override
	public void requestWasRemoved(ClientContext context) {
		// if request is still running, send a GetFailed with code=cancelled
		if( !finished ) {
			synchronized(this) {
				succeeded = false;
				finished = true;
				FetchException cancelled = new FetchException(FetchException.CANCELLED);
				getFailedMessage = new GetFailedMessage(cancelled, identifier, global);
			}
			trySendDataFoundOrGetFailed(null);
		}
		// notify client that request was removed
		FCPMessage msg = new PersistentRequestRemovedMessage(getIdentifier(), global);
		if(persistenceType != PERSIST_CONNECTION) {
		client.queueClientRequestMessage(msg, 0);
		}

		freeData();

		super.requestWasRemoved(context);
	}

	@Override
	public void receive(ClientEvent ce, ClientContext context) {
		// Don't need to lock, verbosity is final and finished is never unset.
		if(finished) return;
		final FCPMessage progress;
		final int verbosityMask;
		if(ce instanceof SplitfileProgressEvent) {
			verbosityMask = ClientGet.VERBOSITY_SPLITFILE_PROGRESS;
			if((verbosity & verbosityMask) == 0)
				return;
			lastActivity = System.currentTimeMillis();
			progress =
				new SimpleProgressMessage(identifier, global, (SplitfileProgressEvent)ce);
		} else if(ce instanceof SendingToNetworkEvent) {
			verbosityMask = ClientGet.VERBOSITY_SENT_TO_NETWORK;
			if((verbosity & verbosityMask) == 0)
				return;
			progress = new SendingToNetworkMessage(identifier, global);
		} else if(ce instanceof SplitfileCompatibilityModeEvent) {
			verbosityMask = ClientGet.VERBOSITY_COMPATIBILITY_MODE;
			if((verbosity & verbosityMask) == 0)
				return;
			SplitfileCompatibilityModeEvent event = (SplitfileCompatibilityModeEvent)ce;
			progress = new CompatibilityMode(identifier, global, event.minCompatibilityMode, event.maxCompatibilityMode, event.splitfileCryptoKey, event.dontCompress, event.bottomLayer);
		} else if(ce instanceof ExpectedHashesEvent) {
			verbosityMask = ClientGet.VERBOSITY_EXPECTED_HASHES;
			if((verbosity & verbosityMask) == 0)
				return;
			ExpectedHashesEvent event = (ExpectedHashesEvent)ce;
			progress = new ExpectedHashes(event, identifier, global);
		} else if(ce instanceof ExpectedMIMEEvent) {
			verbosityMask = VERBOSITY_EXPECTED_TYPE;
			if((verbosity & verbosityMask) == 0)
				return;
			ExpectedMIMEEvent event = (ExpectedMIMEEvent)ce;
			progress = new ExpectedMIME(identifier, global, event.expectedMIMEType);
		} else if(ce instanceof ExpectedFileSizeEvent) {
			verbosityMask = VERBOSITY_EXPECTED_SIZE;
			if((verbosity & verbosityMask) == 0)
				return;
			ExpectedFileSizeEvent event = (ExpectedFileSizeEvent)ce;
			progress = new ExpectedDataLength(identifier, global, event.expectedSize);
		} else if(ce instanceof EnterFiniteCooldownEvent) {
			verbosityMask = VERBOSITY_ENTER_FINITE_COOLDOWN;
			if((verbosity & verbosityMask) == 0)
				return;
			EnterFiniteCooldownEvent event = (EnterFiniteCooldownEvent)ce;
			progress = new EnterFiniteCooldown(identifier, global, event.wakeupTime);
		}
		else return; // Don't know what to do with event
		if(persistenceType == PERSIST_FOREVER && context.jobRunner.hasStarted()) {
			try {
				context.jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						trySendProgress(progress, verbosityMask, null);
						return false;
					}

				}, NativeThread.HIGH_PRIORITY);
			} catch (PersistenceDisabledException e) {
				// Not much we can do
			}
		} else {
			trySendProgress(progress, verbosityMask, null);
		}
	}

	@Override
	protected ClientRequester getClientRequest() {
		return getter;
	}

	@Override
	protected void freeData() {
		Bucket data;
		synchronized(this) {
			data = returnBucket;
			returnBucket = null;
		}
		if(data != null) {
			data.free();
		}
	}

	@Override
	public boolean hasSucceeded() {
		return succeeded;
	}

	public boolean isDirect() {
		return this.returnType == ClientGetMessage.RETURN_TYPE_DIRECT;
	}

	public boolean isToDisk() {
		return this.returnType == ClientGetMessage.RETURN_TYPE_DISK;
	}

	public FreenetURI getURI() {
		return uri;
	}

	public long getDataSize() {
		if(foundDataLength > 0)
			return foundDataLength;
		if(getter != null) {
			return getter.expectedSize();
		}
		return -1;
	}

	public String getMIMEType() {
		if(foundDataMimeType != null)
			return foundDataMimeType;
		if(getter != null) {
			return getter.expectedMIME();
		}
		return null;
	}

	public File getDestFilename() {
		return targetFile;
	}

	@Override
	public double getSuccessFraction() {
		if(progressPending != null) {
			return progressPending.getFraction();
		} else
			return -1;
	}

	@Override
	public double getTotalBlocks() {
		if(progressPending != null) {
			return progressPending.getTotalBlocks();
		} else
			return 1;
	}

	@Override
	public double getMinBlocks() {
		if(progressPending != null) {
			return progressPending.getMinBlocks();
		} else
			return 1;
	}

	@Override
	public double getFailedBlocks() {
		if(progressPending != null) {
			return progressPending.getFailedBlocks();
		} else
			return 0;
	}

	@Override
	public double getFatalyFailedBlocks() {
		if(progressPending != null) {
			return progressPending.getFatalyFailedBlocks();
		} else
			return 0;
	}

	@Override
	public double getFetchedBlocks() {
		if(progressPending != null) {
			return progressPending.getFetchedBlocks();
		} else
			return 0;
	}
	
	public InsertContext.CompatibilityMode[] getCompatibilityMode() {
		if(compatMessage != null) {
			return compatMessage.getModes();
		} else
			return new InsertContext.CompatibilityMode[] { InsertContext.CompatibilityMode.COMPAT_UNKNOWN, InsertContext.CompatibilityMode.COMPAT_UNKNOWN };
	}
	
	public boolean getDontCompress() {
		if(compatMessage == null) return false;
		return compatMessage.dontCompress;
	}
	
	public byte[] getOverriddenSplitfileCryptoKey() {
		if(compatMessage != null) {
			return compatMessage.cryptoKey;
		} else
			return null;
	}

	@Override
	public String getFailureReason(boolean longDescription) {
		if(getFailedMessage == null)
			return null;
		String s = getFailedMessage.shortCodeDescription;
		if(longDescription && getFailedMessage.extraDescription != null)
			s += ": "+getFailedMessage.extraDescription;
		return s;
	}
	
	GetFailedMessage getFailureMessage() {
		if(getFailedMessage == null) return null;
		return getFailedMessage;
	}
	
	public int getFailureReasonCode() {
		if(getFailedMessage == null)
			return -1;
		return getFailedMessage.code;
		
	}

	@Override
	public boolean isTotalFinalized() {
		if(finished && succeeded) return true;
		if(progressPending == null) return false;
		else {
			return progressPending.isTotalFinalized();
		}
	}

	public Bucket getFinalBucket() {
		synchronized(this) {
			if(!finished) return null;
			if(!succeeded) return null;
			return returnBucket;
		}
	}
	
	/**
	 * Returns the {@link Bucket} that contains the downloaded data.
	 *
	 * @return The data in a {@link Bucket}, or <code>null</code> if this
	 *         isn&rsquo;t applicable
	 */
	public Bucket getBucket() {
		synchronized(this) {
			if(targetFile != null) {
				if(succeeded || tempFile == null) {
					return new FileBucket(targetFile, false, true, false, false, false);
				} else {
					return new FileBucket(tempFile, false, true, false, false, false);
				}
			} else return returnBucket;
		}
	}

	@Override
	public boolean canRestart() {
		if(!finished) {
			Logger.minor(this, "Cannot restart because not finished for "+identifier);
			return false;
		}
		if(succeeded) {
			Logger.minor(this, "Cannot restart because succeeded for "+identifier);
			return false;
		}
		return getter.canRestart();
	}

	@Override
	public boolean restart(ClientContext context, final boolean disableFilterData) {
		if(!canRestart()) return false;
		FreenetURI redirect = null;
		synchronized(this) {
			finished = false;
			if(persistenceType == PERSIST_FOREVER && getFailedMessage != null) {
				if(getFailedMessage.redirectURI != null) {
					redirect =
						getFailedMessage.redirectURI.clone();
				}
			} else if(getFailedMessage != null)
				redirect = getFailedMessage.redirectURI;
			this.getFailedMessage = null;
			this.progressPending = null;
			compatMessage = null;
			expectedHashes = null;
			started = false;
			if(disableFilterData)
				fctx.filterData = false;
		}
		if(client != null) {
			RequestStatusCache cache = client.getRequestStatusCache();
			if(cache != null) {
				cache.updateStarted(identifier, redirect);
			}
		}
		try {
			if(getter.restart(redirect, fctx.filterData, context)) {
				synchronized(this) {
					if(redirect != null) {
						this.uri = redirect;
					}
					started = true;
				}
			}
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.updateStarted(identifier, true);
				}
			}
			return true;
		} catch (FetchException e) {
			onFailure(e, null);
			return false;
		}
	}

	public synchronized boolean hasPermRedirect() {
		return getFailedMessage != null && getFailedMessage.redirectURI != null;
	}

	public boolean filterData() {
		return fctx.filterData;
	}

	@Override
	RequestStatus getStatus() {
		boolean totalFinalized = false;
		int total = 0, min = 0, fetched = 0, fatal = 0, failed = 0;
		if(progressPending != null) {
			totalFinalized = progressPending.isTotalFinalized();
			// FIXME why are these doubles???
			total = (int) progressPending.getTotalBlocks();
			min = (int) progressPending.getMinBlocks();
			fetched = (int) progressPending.getFetchedBlocks();
			fatal = (int) progressPending.getFatalyFailedBlocks();
			failed = (int) progressPending.getFailedBlocks();
		}
		if(finished && succeeded) totalFinalized = true;
		int failureCode = -1;
		String failureReasonShort = null;
		String failureReasonLong = null;
		if(getFailedMessage != null) {
			failureCode = getFailedMessage.code;
			failureReasonShort = getFailedMessage.getShortFailedMessage();
			failureReasonShort = getFailedMessage.getLongFailedMessage();
		}
		String mimeType = foundDataMimeType;
		long dataSize = foundDataLength;
		if(getter != null) {
			if(mimeType == null)
				mimeType = getter.expectedMIME();
			if(dataSize <= 0)
				dataSize = getter.expectedSize();
		}
		File target = getDestFilename();
		if(target != null)
			target = new File(target.getPath());
		
		Bucket shadow = getFinalBucket();
		if(shadow != null) {
			dataSize = shadow.size();
			shadow = shadow.createShadow();
		}
		
		boolean filterData;
		boolean overriddenDataType;
		filterData = fctx.filterData;
		overriddenDataType = fctx.overrideMIME != null || fctx.charset != null;
		
		return new DownloadRequestStatus(identifier, persistenceType, started, finished, 
				succeeded, total, min, fetched, fatal, failed, totalFinalized, 
				lastActivity, priorityClass, failureCode, mimeType, dataSize, target, 
				getCompatibilityMode(), getOverriddenSplitfileCryptoKey(), 
				getURI().clone(), failureReasonShort, failureReasonLong, overriddenDataType, shadow, filterData, getDontCompress());
	}

	private static final long CLIENT_DETAIL_MAGIC = 0x67145b675d2e22f4L;
	private static final int CLIENT_DETAIL_VERSION = 1;

    @Override
    public void getClientDetail(DataOutputStream dos) throws IOException {
        dos.writeLong(CLIENT_DETAIL_MAGIC);
        dos.writeLong(CLIENT_DETAIL_VERSION);
        dos.writeShort(returnType);
        writeFile(targetFile, dos);
        writeFile(tempFile, dos);
        dos.writeBoolean(binaryBlob);
        fctx.writeTo(dos);
        super.getClientDetail(dos);
    }

    private static void writeFile(File f, DataOutputStream dos) throws IOException {
        if(f == null)
            dos.writeUTF("");
        else
            dos.writeUTF(f.toString());
    }
    
    @Override
    public void onResume(ClientContext context) {
        super.onResume(context);
        if(getter == null) {
            if(returnBucket != null) returnBucket.onResume(context);
        } // Otherwise the returnBucket is the ClientGetter's responsibility.
    }

    @Override
    RequestType getType() {
        return RequestType.GET;
    }
}
