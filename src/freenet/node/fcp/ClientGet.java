/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertContext;
import freenet.client.async.BinaryBlob;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.ExpectedHashesEvent;
import freenet.client.events.SendingToNetworkEvent;
import freenet.client.events.SplitfileCompatibilityModeEvent;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;
import freenet.support.io.NullBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

/**
 * A simple client fetch. This can of course fetch arbitrarily large
 * files, including splitfiles, redirects, etc.
 */
public class ClientGet extends ClientRequest implements ClientGetCallback, ClientEventListener {

	/** Fetch context. Never passed in: always created new by the ClientGet. Therefore, we
	 * can safely delete it in requestWasRemoved(). */
	private final FetchContext fctx;
	private final ClientGetter getter;
	private final short returnType;
	private final File targetFile;
	private final File tempFile;
	/** Bucket passed in to the ClientGetter to return data in. Null unless returntype=disk */
	private Bucket returnBucket;
	private final boolean binaryBlob;

	// Verbosity bitmasks
	private static final int VERBOSITY_SPLITFILE_PROGRESS = 1;
	private static final int VERBOSITY_SENT_TO_NETWORK = 2;
	private static final int VERBOSITY_COMPATIBILITY_MODE = 4;
	private static final int VERBOSITY_EXPECTED_HASHES = 8;

	// Stuff waiting for reconnection
	/** Did the request succeed? Valid if finished. */
	private boolean succeeded;
	/** Length of the found data */
	private long foundDataLength = -1;
	/** MIME type of the found data */
	private String foundDataMimeType;
	/** Details of request failure */
	private GetFailedMessage getFailedMessage;
	/** Succeeded but failed to return data e.g. couldn't write to file */
	private ProtocolErrorMessage postFetchProtocolErrorMessage;
	/** AllData (the actual direct-send data) - do not persist, because the bucket
	 * is not persistent. FIXME make the bucket persistent! */
	private AllDataMessage allDataPending;
	/** Last progress message. Not persistent - FIXME this will be made persistent
	 * when we have proper persistence at the ClientGetter level. */
	private SimpleProgressMessage progressPending;
	/** Have we received a SendingToNetworkEvent? */
	private boolean sentToNetwork;
	private CompatibilityMode compatMessage;
	private ExpectedHashes expectedHashes;

	/**
	 * Create one for a global-queued request not made by FCP.
	 * @throws IdentifierCollisionException
	 * @throws NotAllowedException
	 * @throws IOException
	 */
	public ClientGet(FCPClient globalClient, FreenetURI uri, boolean dsOnly, boolean ignoreDS,
			boolean filterData, int maxSplitfileRetries, int maxNonSplitfileRetries,
			long maxOutputLength, short returnType, boolean persistRebootOnly, String identifier, int verbosity,
			short prioClass, File returnFilename, File returnTempFilename, String charset, boolean writeToClientCache, FCPServer server, ObjectContainer container) throws IdentifierCollisionException, NotAllowedException, IOException {
		super(uri, identifier, verbosity, charset, null, globalClient,
				prioClass,
				(persistRebootOnly ? ClientRequest.PERSIST_REBOOT : ClientRequest.PERSIST_FOREVER), null, true, container);

		fctx = new FetchContext(server.defaultFetchContext, FetchContext.IDENTICAL_MASK, false, null);
		fctx.eventProducer.addEventListener(this);
		fctx.localRequestOnly = dsOnly;
		fctx.ignoreStore = ignoreDS;
		fctx.maxNonSplitfileRetries = maxNonSplitfileRetries;
		fctx.maxSplitfileBlockRetries = maxSplitfileRetries;
		fctx.filterData = filterData;
		fctx.maxOutputLength = maxOutputLength;
		fctx.maxTempLength = maxOutputLength;
		fctx.canWriteClientCache = writeToClientCache;
		Bucket ret = null;
		this.returnType = returnType;
		binaryBlob = false;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			this.targetFile = returnFilename;
			this.tempFile = returnTempFilename;
			if(!(server.core.allowDownloadTo(returnTempFilename) && server.core.allowDownloadTo(returnFilename)))
				throw new NotAllowedException();
			ret = new FileBucket(returnTempFilename, false, true, false, false, false);
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
					lowLevelClient,
					returnBucket, null);
	}

	public ClientGet(FCPConnectionHandler handler, ClientGetMessage message, FCPServer server, ObjectContainer container) throws IdentifierCollisionException, MessageInvalidException {
		super(message.uri, message.identifier, message.verbosity, message.charset, handler,
				message.priorityClass, message.persistenceType, message.clientToken, message.global, container);
		// Create a Fetcher directly in order to get more fine-grained control,
		// since the client may override a few context elements.
		fctx = new FetchContext(server.defaultFetchContext, FetchContext.IDENTICAL_MASK, false, null);
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

		if(message.allowedMIMETypes != null) {
			fctx.allowedMIMETypes = new HashSet<String>();
			for(String mime : message.allowedMIMETypes)
				fctx.allowedMIMETypes.add(mime);
		}

		this.returnType = message.returnType;
		this.binaryBlob = message.binaryBlob;
		Bucket ret = null;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			this.targetFile = message.diskFile;
			this.tempFile = message.tempFile;
			if(!(server.core.allowDownloadTo(tempFile) && server.core.allowDownloadTo(targetFile)))
				throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Not allowed to download to "+tempFile+" or "+targetFile, identifier, global);
			else if(!(handler.allowDDAFrom(tempFile, true) && handler.allowDDAFrom(targetFile, true)))
				throw new MessageInvalidException(ProtocolErrorMessage.DIRECT_DISK_ACCESS_DENIED, "Not allowed to download to "+tempFile+" or "+targetFile + ". You might need to do a " + TestDDARequestMessage.NAME + " first.", identifier, global);
			ret = new FileBucket(message.tempFile, false, true, false, false, false);
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
					lowLevelClient,
					binaryBlob ? new NullBucket() : returnBucket, binaryBlob ? returnBucket : null);
	}

	/**
	 * Create a ClientGet from a request serialized to a SimpleFieldSet.
	 * Can throw, and does minimal verification, as is dealing with data
	 * supposedly serialized out by the node.
	 * @throws IOException
	 * @throws FetchException
	 */
	public ClientGet(SimpleFieldSet fs, FCPClient client2, FCPServer server) throws IOException, FetchException {
		super(fs, client2);

		returnType = ClientGetMessage.parseValidReturnType(fs.get("ReturnType"));
		String f = fs.get("Filename");
		if(f != null)
			targetFile = new File(f);
		else
			targetFile = null;
		f = fs.get("TempFilename");
		if(f != null)
			tempFile = new File(f);
		else
			tempFile = null;
		boolean ignoreDS = Fields.stringToBool(fs.get("IgnoreDS"), false);
		boolean dsOnly = Fields.stringToBool(fs.get("DSOnly"), false);
		int maxRetries = Integer.parseInt(fs.get("MaxRetries"));
		boolean filterData = Fields.stringToBool(fs.get("FilterData"),false);
		fctx = new FetchContext(server.defaultFetchContext, FetchContext.IDENTICAL_MASK, false, null);
		fctx.eventProducer.addEventListener(this);
		// ignoreDS
		fctx.localRequestOnly = dsOnly;
		fctx.ignoreStore = ignoreDS;
		fctx.maxNonSplitfileRetries = maxRetries;
		fctx.maxSplitfileBlockRetries = maxRetries;
		fctx.filterData = filterData;
		binaryBlob = Fields.stringToBool(fs.get("BinaryBlob"), false);
		succeeded = Fields.stringToBool(fs.get("Succeeded"), false);
		if(finished) {
			if(succeeded) {
				foundDataLength = Long.parseLong(fs.get("FoundDataLength"));
				foundDataMimeType = fs.get("FoundDataMimeType");
				SimpleFieldSet fs1 = fs.subset("PostFetchProtocolError");
				if(fs1 != null)
					postFetchProtocolErrorMessage = new ProtocolErrorMessage(fs1);
			} else {
				getFailedMessage = new GetFailedMessage(fs.subset("GetFailed"), false);
			}
		}
		Bucket ret = null;
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
			if (succeeded) {
				ret = new FileBucket(targetFile, false, true, false, false, false);
			} else {
				ret = new FileBucket(tempFile, false, true, false, false, false);
			}
		} else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
			ret = new NullBucket();
		} else if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT) {
			try {
				ret = SerializableToFieldSetBucketUtil.create(fs.subset("ReturnBucket"), server.core.random, server.core.persistentTempBucketFactory);
				if(ret == null) throw new CannotCreateFromFieldSetException("ret == null");
			} catch (CannotCreateFromFieldSetException e) {
				Logger.error(this, "Cannot read: "+this+" : "+e, e);
				try {
					// Create a new temp bucket
					if(persistenceType == PERSIST_FOREVER)
						ret = server.core.persistentTempBucketFactory.makeBucket(fctx.maxOutputLength);
					else
						ret = server.core.tempBucketFactory.makeBucket(fctx.maxOutputLength);
				} catch (IOException e1) {
					Logger.error(this, "Cannot create bucket for temp storage: "+e, e);
					getter = null;
					returnBucket = null;
					throw new FetchException(FetchException.BUCKET_ERROR, e);
				}
			}
		} else {
			throw new IllegalArgumentException();
		}
		if(succeeded) {
			if(foundDataLength < ret.size()) {
				Logger.error(this, "Failing "+identifier+" because lost data");
				succeeded = false;
			}
		}
		if(ret == null)
			Logger.error(this, "Impossible: ret = null in SFS constructor for "+this, new Exception("debug"));
		returnBucket = ret;

		String[] allowed = fs.getAll("AllowedMIMETypes");
		if(allowed != null) {
			fctx.allowedMIMETypes = new HashSet<String>();
			for (String a : allowed)
				fctx.allowedMIMETypes.add(a);
		}

		getter = new ClientGetter(this,
				uri,
				fctx, priorityClass,
				lowLevelClient,
				binaryBlob ? new NullBucket() : returnBucket,
						binaryBlob ? returnBucket : null);

		if(finished && succeeded)
			allDataPending = new AllDataMessage(returnBucket, identifier, global, startupTime, completionTime, this.foundDataMimeType);
	}

	/**
	 * Must be called just after construction, but within a transaction.
	 * @throws IdentifierCollisionException If the identifier is already in use.
	 */
	@Override
	void register(ObjectContainer container, boolean noTags) throws IdentifierCollisionException {
		if(client != null)
			assert(this.persistenceType == client.persistenceType);
		if(persistenceType != PERSIST_CONNECTION)
			try {
				client.register(this, container);
			} catch (IdentifierCollisionException e) {
				returnBucket.free();
				if(persistenceType == PERSIST_FOREVER)
					returnBucket.removeFrom(container);
				throw e;
			}
			if(persistenceType != PERSIST_CONNECTION && !noTags) {
				FCPMessage msg = persistentTagMessage(container);
				client.queueClientRequestMessage(msg, 0, container);
			}
	}

	@Override
	public void start(ObjectContainer container, ClientContext context) {
		try {
			synchronized(this) {
				if(finished) return;
			}
			getter.start(container, context);
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage(container);
				client.queueClientRequestMessage(msg, 0, container);
			}
			synchronized(this) {
				started = true;
			}
		} catch (FetchException e) {
			synchronized(this) {
				started = true;
			} // before the failure handler
			onFailure(e, null, container);
		} catch (Throwable t) {
			synchronized(this) {
				started = true;
			}
			onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), null, container);
		}
		if(persistenceType == PERSIST_FOREVER)
			container.store(this); // Update
	}

	@Override
	public void onLostConnection(ObjectContainer container, ClientContext context) {
		if(persistenceType == PERSIST_CONNECTION)
			cancel(container, context);
		// Otherwise ignore
	}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		Logger.minor(this, "Succeeded: "+identifier);
		Bucket data = result.asBucket();
		if(persistenceType == PERSIST_FOREVER) {
			if(data != null)
				container.activate(data, 5);
			if(returnBucket != null)
				container.activate(returnBucket, 5);
			container.activate(client, 1);
			if(tempFile != null)
				container.activate(tempFile, 5);
			if(targetFile != null)
				container.activate(targetFile, 5);
		}
		if(returnBucket != data && !binaryBlob) {
			boolean failed = true;
			synchronized(this) {
				if(finished) {
					Logger.error(this, "Already finished but onSuccess() for "+this+" data = "+data, new Exception("debug"));
					data.free();
					if(persistenceType == PERSIST_FOREVER) data.removeFrom(container);
					return; // Already failed - bucket error maybe??
				}
				if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT && returnBucket == null) {
					// Lost bucket for some reason e.g. bucket error (caused by IOException) on previous try??
					// Recover...
					returnBucket = data;
					failed = false;
				}
			}
			if(failed && persistenceType == PERSIST_FOREVER) {
				if(container.ext().getID(returnBucket) == container.ext().getID(data)) {
					Logger.error(this, "DB4O BUG DETECTED WITHOUT ARRAY HANDLING! EVIL HORRIBLE BUG! UID(returnBucket)="+container.ext().getID(returnBucket)+" for "+returnBucket+" active="+container.ext().isActive(returnBucket)+" stored = "+container.ext().isStored(returnBucket)+" but UID(data)="+container.ext().getID(data)+" for "+data+" active = "+container.ext().isActive(data)+" stored = "+container.ext().isStored(data));
					// Succeed anyway, hope that the returned bucket is consistent...
					returnBucket = data;
					failed = false;
				}
			}
			if(failed) {
				Logger.error(this, "returnBucket = "+returnBucket+" but onSuccess() data = "+data, new Exception("debug"));
				// Caller guarantees that data == returnBucket
				onFailure(new FetchException(FetchException.INTERNAL_ERROR, "Data != returnBucket"), null, container);
				return;
			}
		}
		boolean dontFree = false;
		// FIXME I don't think this is a problem in this case...? (Disk write while locked..)
		AllDataMessage adm = null;
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

			if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT) {
				// Send all the data at once
				// FIXME there should be other options
				// FIXME: CompletionTime is set on finish() : we need to give it current time here
				// but it means we won't always return the same value to clients... Does it matter ?
				adm = new AllDataMessage(returnBucket, identifier, global, startupTime, System.currentTimeMillis(), this.foundDataMimeType);
				if(persistenceType == PERSIST_CONNECTION)
					adm.setFreeOnSent();
				dontFree = true;
				/*
				 * } else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
				// Do nothing
				 */
			} else if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
				// Write to temp file, then rename over filename
				if(!FileUtil.renameTo(tempFile, targetFile)) {
					postFetchProtocolErrorMessage = new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_RENAME_FILE, false, null, identifier, global);
					// Don't delete temp file, user might want it.
				}
				returnBucket = new FileBucket(targetFile, false, true, false, false, false);
			}
			if(persistenceType == PERSIST_FOREVER && progressPending != null) {
				container.activate(progressPending, 1);
				progressPending.removeFrom(container);
			}
			progressPending = null;
			this.foundDataLength = returnBucket.size();
			this.succeeded = true;
			finished = true;
		}
		trySendDataFoundOrGetFailed(null, container);

		if(adm != null)
			trySendAllDataMessage(adm, null, container);
		if(!dontFree) {
			data.free();
		}
		if(persistenceType == PERSIST_FOREVER) {
			returnBucket.storeTo(container);
			container.store(this);
		}
		finish(container);
		if(client != null)
			client.notifySuccess(this, container);
	}

	private void trySendDataFoundOrGetFailed(FCPConnectionOutputHandler handler, ObjectContainer container) {
		FCPMessage msg;

		// Don't need to lock. succeeded is only ever set, never unset.
		// and succeeded and getFailedMessage are both atomic.
		if(succeeded) {
			msg = new DataFoundMessage(foundDataLength, foundDataMimeType, identifier, global);
		} else {
			msg = getFailedMessage;
			if(persistenceType == PERSIST_FOREVER)
				container.activate(msg, 5);
		}

		if(handler == null && persistenceType == PERSIST_CONNECTION)
			handler = origHandler.outputHandler;
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, 0, container);
		if(postFetchProtocolErrorMessage != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(postFetchProtocolErrorMessage, 5);
			if(handler != null)
				handler.queue(postFetchProtocolErrorMessage);
			else {
				if(persistenceType == PERSIST_FOREVER)
					container.activate(client, 1);
				client.queueClientRequestMessage(postFetchProtocolErrorMessage, 0, container);
			}
		}

	}

	private void trySendAllDataMessage(AllDataMessage msg, FCPConnectionOutputHandler handler, ObjectContainer container) {
		if(persistenceType != ClientRequest.PERSIST_CONNECTION) {
			allDataPending = msg;
			if(persistenceType == ClientRequest.PERSIST_FOREVER) {
				container.store(this);
			}
			return;
		}
        if(handler == null)
            handler = origHandler.outputHandler;

		handler.queue(msg);
	}

	private void trySendProgress(FCPMessage msg, FCPConnectionOutputHandler handler, ObjectContainer container) {
		int verbosityMask = 0;
		if(persistenceType != ClientRequest.PERSIST_CONNECTION) {
			FCPMessage oldProgress = null;
			if(msg instanceof SimpleProgressMessage) {
				oldProgress = progressPending;
				progressPending = (SimpleProgressMessage)msg;
				verbosityMask = ClientGet.VERBOSITY_SPLITFILE_PROGRESS;
			} else if(msg instanceof SendingToNetworkMessage) {
				sentToNetwork = true;
				verbosityMask = ClientGet.VERBOSITY_SENT_TO_NETWORK;
			} else if(msg instanceof CompatibilityMode) {
				CompatibilityMode compat = (CompatibilityMode)msg;
				if(compatMessage != null) {
					if(persistenceType == PERSIST_FOREVER) container.activate(compatMessage, 1);
					compatMessage.merge(compat.min, compat.max, compat.cryptoKey, compat.dontCompress, compat.bottomLayer);
					if(persistenceType == PERSIST_FOREVER) container.store(compatMessage);
				} else {
					compatMessage = compat;
					if(persistenceType == PERSIST_FOREVER) {
						container.store(compatMessage);
						container.store(this);
					}
				}
				verbosityMask = ClientGet.VERBOSITY_COMPATIBILITY_MODE;
			} else if(msg instanceof ExpectedHashes) {
				if(expectedHashes != null) {
					Logger.error(this, "Got a new ExpectedHashes", new Exception("debug"));
				} else {
					this.expectedHashes = (ExpectedHashes)msg;
					if(persistenceType == PERSIST_FOREVER) {
						container.store(this);
					}
				}
				verbosityMask = ClientGet.VERBOSITY_EXPECTED_HASHES;
			} else
				verbosityMask = -1;
			if(persistenceType == ClientRequest.PERSIST_FOREVER) {
				container.store(this);
				if(oldProgress != null) {
					container.activate(oldProgress, 1);
					oldProgress.removeFrom(container);
				}
			}
		} else {
			if(msg instanceof SimpleProgressMessage)
				verbosityMask = ClientGet.VERBOSITY_SPLITFILE_PROGRESS;
			else if(msg instanceof SendingToNetworkMessage)
				verbosityMask = ClientGet.VERBOSITY_SENT_TO_NETWORK;
			else if(msg instanceof CompatibilityMode)
				verbosityMask = ClientGet.VERBOSITY_COMPATIBILITY_MODE;
			else if(msg instanceof ExpectedHashes)
				verbosityMask = ClientGet.VERBOSITY_EXPECTED_HASHES;
			else
				verbosityMask = -1;
		}
		if(persistenceType == PERSIST_FOREVER)
			container.activate(client, 1);
		if(persistenceType == PERSIST_CONNECTION && handler == null)
			handler = origHandler.outputHandler;
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, verbosityMask, container);
		if(persistenceType == PERSIST_FOREVER && !client.isGlobalQueue)
			container.deactivate(client, 1);
	}

	@Override
	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData, ObjectContainer container) {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION) {
			Logger.error(this, "WTF? persistenceType="+persistenceType, new Exception("error"));
			return;
		}
		if(!onlyData) {
			if(includePersistentRequest) {
				FCPMessage msg = persistentTagMessage(container);
				handler.queue(msg);
			}
			if(progressPending != null) {
				if(persistenceType == PERSIST_FOREVER)
					container.activate(progressPending, 5);
				handler.queue(progressPending);
			}
			if(sentToNetwork)
				handler.queue(new SendingToNetworkMessage(identifier, global));
			if(finished)
				trySendDataFoundOrGetFailed(handler, container);
		}

		if (onlyData && allDataPending  == null) {
			Logger.error(this, "No data pending !");
		}

		if(includeData && (allDataPending != null)) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(allDataPending, 5);
			handler.queue(allDataPending);
		}
		
		if(compatMessage != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(compatMessage, 5);
			handler.queue(compatMessage);
		}
		
		if(expectedHashes != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(expectedHashes, Integer.MAX_VALUE);
			handler.queue(expectedHashes);
		}
	}

	@Override
	protected FCPMessage persistentTagMessage(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(uri, 5);
			container.activate(fctx, 1);
			container.activate(client, 1);
			container.activate(targetFile, 5);
			container.activate(tempFile, 5);
		}
		return new PersistentGet(identifier, uri, verbosity, priorityClass, returnType, persistenceType, targetFile, tempFile, clientToken, client.isGlobalQueue, started, fctx.maxNonSplitfileRetries, binaryBlob, fctx.maxOutputLength);
	}

	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		if(finished) return;
		synchronized(this) {
			succeeded = false;
			getFailedMessage = new GetFailedMessage(e, identifier, global);
			finished = true;
			started = true;
		}
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Caught "+e, e);
		trySendDataFoundOrGetFailed(null, container);
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(client, 1);
		}
		// We do not want the data to be removed on failure, because the request
		// may be restarted, and the bucket persists on the getter, even if we get rid of it here.
		//freeData(container);
		finish(container);
		if(client != null)
			client.notifyFailure(this, container);
		if(persistenceType == PERSIST_FOREVER)
			container.store(this);
	}

	@Override
	public void requestWasRemoved(ObjectContainer container, ClientContext context) {
		// if request is still running, send a GetFailed with code=cancelled
		if( !finished ) {
			synchronized(this) {
				succeeded = false;
				finished = true;
				FetchException cancelled = new FetchException(FetchException.CANCELLED);
				getFailedMessage = new GetFailedMessage(cancelled, identifier, global);
			}
			trySendDataFoundOrGetFailed(null, container);
		}
		// notify client that request was removed
		FCPMessage msg = new PersistentRequestRemovedMessage(getIdentifier(), global);
		if(persistenceType != PERSIST_CONNECTION) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(client, 1);
		client.queueClientRequestMessage(msg, 0, container);
		}

		freeData(container);

		if(persistenceType == PERSIST_FOREVER) {
			container.activate(fctx, 1);
			if(fctx.allowedMIMETypes != null) {
				container.activate(fctx.allowedMIMETypes, 5);
				container.delete(fctx.allowedMIMETypes);
			}
			fctx.removeFrom(container);
			getter.removeFrom(container, context);
			if(targetFile != null)
				container.delete(targetFile);
			if(tempFile != null)
				container.delete(tempFile);
			if(getFailedMessage != null) {
				container.activate(getFailedMessage, 5);
				getFailedMessage.removeFrom(container);
			}
			if(postFetchProtocolErrorMessage != null) {
				container.activate(postFetchProtocolErrorMessage, 5);
				postFetchProtocolErrorMessage.removeFrom(container);
			}
			if(allDataPending != null) {
				container.activate(allDataPending, 5);
				allDataPending.removeFrom(container);
			}
			if(progressPending != null) {
				container.activate(progressPending, 5);
				progressPending.removeFrom(container);
			}
			if(compatMessage != null) {
				container.activate(compatMessage, 5);
				compatMessage.removeFrom(container);
			}
			if(expectedHashes != null) {
				container.activate(expectedHashes, Integer.MAX_VALUE);
				expectedHashes.removeFrom(container);
			}
		}
		super.requestWasRemoved(container, context);
	}

	public void receive(ClientEvent ce, ObjectContainer container, ClientContext context) {
		// Don't need to lock, verbosity is final and finished is never unset.
		if(finished) return;
		final FCPMessage progress;
		if(ce instanceof SplitfileProgressEvent) {
			if(!((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS))
				return;
			lastActivity = System.currentTimeMillis();
			progress =
				new SimpleProgressMessage(identifier, global, (SplitfileProgressEvent)ce);
		} else if(ce instanceof SendingToNetworkEvent) {
			if(!((verbosity & VERBOSITY_SENT_TO_NETWORK) == VERBOSITY_SENT_TO_NETWORK))
				return;
			progress = new SendingToNetworkMessage(identifier, global);
		} else if(ce instanceof SplitfileCompatibilityModeEvent) {
			SplitfileCompatibilityModeEvent event = (SplitfileCompatibilityModeEvent)ce;
			progress = new CompatibilityMode(identifier, global, event.minCompatibilityMode, event.maxCompatibilityMode, event.splitfileCryptoKey, event.dontCompress, event.bottomLayer);
		} else if(ce instanceof ExpectedHashesEvent) {
			ExpectedHashesEvent event = (ExpectedHashesEvent)ce;
			progress = new ExpectedHashes(event, identifier, global);
		}
		else return; // Don't know what to do with event
		// container may be null...
		if(persistenceType == PERSIST_FOREVER && container == null) {
			try {
				context.jobRunner.queue(new DBJob() {

					public boolean run(ObjectContainer container, ClientContext context) {
						trySendProgress(progress, null, container);
						return false;
					}

				}, NativeThread.HIGH_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				// Not much we can do
			}
		} else {
			trySendProgress(progress, null, container);
		}
	}

	@Override
	protected ClientRequester getClientRequest() {
		return getter;
	}

	@Override
	protected void freeData(ObjectContainer container) {
		Bucket data;
		synchronized(this) {
			data = returnBucket;
			returnBucket = null;
		}
		if(data != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(data, 5);
			data.free();
			if(persistenceType == PERSIST_FOREVER)
				data.removeFrom(container);
			if(persistenceType == PERSIST_FOREVER)
				container.store(this);
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

	public FreenetURI getURI(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(uri, 5);
		return uri;
	}

	public long getDataSize(ObjectContainer container) {
		if(foundDataLength > 0)
			return foundDataLength;
		if(getter != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(getter, 1);
			return getter.expectedSize();
		}
		return -1;
	}

	public String getMIMEType(ObjectContainer container) {
		if(foundDataMimeType != null)
			return foundDataMimeType;
		if(getter != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(getter, 1);
			return getter.expectedMIME();
		}
		return null;
	}

	public File getDestFilename(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(targetFile, 5);
		return targetFile;
	}

	@Override
	public double getSuccessFraction(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressPending != null)
			container.activate(progressPending, 2);
		if(progressPending != null) {
			return progressPending.getFraction();
		} else
			return -1;
	}

	@Override
	public double getTotalBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressPending != null)
			container.activate(progressPending, 2);
		if(progressPending != null) {
			return progressPending.getTotalBlocks();
		} else
			return 1;
	}

	@Override
	public double getMinBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressPending != null)
			container.activate(progressPending, 2);
		if(progressPending != null) {
			return progressPending.getMinBlocks();
		} else
			return 1;
	}

	@Override
	public double getFailedBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressPending != null)
			container.activate(progressPending, 2);
		if(progressPending != null) {
			return progressPending.getFailedBlocks();
		} else
			return 0;
	}

	@Override
	public double getFatalyFailedBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressPending != null)
			container.activate(progressPending, 2);
		if(progressPending != null) {
			return progressPending.getFatalyFailedBlocks();
		} else
			return 0;
	}

	@Override
	public double getFetchedBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressPending != null)
			container.activate(progressPending, 2);
		if(progressPending != null) {
			return progressPending.getFetchedBlocks();
		} else
			return 0;
	}
	
	public InsertContext.CompatibilityMode[] getCompatibilityMode(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && compatMessage != null)
			container.activate(compatMessage, 2);
		if(compatMessage != null) {
			return compatMessage.getModes();
		} else
			return new InsertContext.CompatibilityMode[] { InsertContext.CompatibilityMode.COMPAT_UNKNOWN, InsertContext.CompatibilityMode.COMPAT_UNKNOWN };
	}
	
	public byte[] getOverriddenSplitfileCryptoKey(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && compatMessage != null)
			container.activate(compatMessage, 2);
		if(compatMessage != null) {
			return compatMessage.cryptoKey;
		} else
			return null;
	}

	@Override
	public String getFailureReason(boolean longDescription, ObjectContainer container) {
		if(getFailedMessage == null)
			return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(getFailedMessage, 5);
		String s = getFailedMessage.shortCodeDescription;
		if(longDescription && getFailedMessage.extraDescription != null)
			s += ": "+getFailedMessage.extraDescription;
		return s;
	}

	@Override
	public boolean isTotalFinalized(ObjectContainer container) {
		if(finished && succeeded) return true;
		if(progressPending == null) return false;
		else {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(progressPending, 1);
			return progressPending.isTotalFinalized();
		}
	}

	/**
	 * Returns the {@link Bucket} that contains the downloaded data.
	 *
	 * @return The data in a {@link Bucket}, or <code>null</code> if this
	 *         isn&rsquo;t applicable
	 */
	public Bucket getBucket(ObjectContainer container) {
		synchronized(this) {
			if(targetFile != null) {
				if(succeeded || tempFile == null) {
					if(persistenceType == PERSIST_FOREVER) container.activate(targetFile, 5);
					return new FileBucket(targetFile, false, true, false, false, false);
				} else {
					if(persistenceType == PERSIST_FOREVER) container.activate(tempFile, 5);
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
	public boolean restart(ObjectContainer container, ClientContext context) {
		if(!canRestart()) return false;
		FreenetURI redirect;
		synchronized(this) {
			finished = false;
			redirect =
				getFailedMessage == null ? null : getFailedMessage.redirectURI;
			if(persistenceType == PERSIST_FOREVER && getFailedMessage != null)
				getFailedMessage.removeFrom(container);
			this.getFailedMessage = null;
			if(persistenceType == PERSIST_FOREVER && allDataPending != null)
				allDataPending.removeFrom(container);
			this.allDataPending = null;
			if(persistenceType == PERSIST_FOREVER && postFetchProtocolErrorMessage != null)
				postFetchProtocolErrorMessage.removeFrom(container);
			this.postFetchProtocolErrorMessage = null;
			if(persistenceType == PERSIST_FOREVER && progressPending != null)
				progressPending.removeFrom(container);
			this.progressPending = null;
			if(persistenceType == PERSIST_FOREVER && compatMessage != null)
				compatMessage.removeFrom(container);
			compatMessage = null;
			if(persistenceType == PERSIST_FOREVER && expectedHashes != null)
				expectedHashes.removeFrom(container);
				expectedHashes = null;
			started = false;
		}
		if(persistenceType == PERSIST_FOREVER)
			container.store(this);
		try {
			if(getter.restart(redirect, container, context)) {
				synchronized(this) {
					if(redirect != null) {
						if(persistenceType == PERSIST_FOREVER)
							uri.removeFrom(container);
						this.uri = redirect;
					}
					started = true;
				}
				if(persistenceType == PERSIST_FOREVER)
					container.store(this);
			}
			return true;
		} catch (FetchException e) {
			onFailure(e, null, container);
			return false;
		}
	}

	public synchronized boolean hasPermRedirect() {
		return getFailedMessage != null && getFailedMessage.redirectURI != null;
	}

	public void onRemoveEventProducer(ObjectContainer container) {
		// Do nothing, we called the removeFrom().
	}
}
