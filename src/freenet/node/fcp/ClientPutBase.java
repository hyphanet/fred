package freenet.node.fcp;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientPutCallback;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.ExpectedHashesEvent;
import freenet.client.events.SimpleEventProducer;
import freenet.client.events.SplitfileProgressEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

/**
 * Base class for ClientPut and ClientPutDir.
 * Any code which can be shared between the two goes here.
 */
public abstract class ClientPutBase extends ClientRequest implements ClientPutCallback, ClientEventListener {

	/** Created new for each ClientPutBase, so we have to delete it in requestWasRemoved() */
	final InsertContext ctx;
	final boolean getCHKOnly;

	// Verbosity bitmasks
	private static final int VERBOSITY_SPLITFILE_PROGRESS = 1;
	
	private static final int VERBOSITY_EXPECTED_HASHES = 8; // same as ClientGet
	private static final int VERBOSITY_PUT_FETCHABLE = 256;
	private static final int VERBOSITY_COMPRESSION_START_END = 512;

	// Stuff waiting for reconnection
	/** Has the request succeeded? */
	protected boolean succeeded;
	/** If the request failed, how did it fail? PutFailedMessage is the most
	 * convenient way to store this (InsertException has a stack trace!).
	 */
	protected PutFailedMessage putFailedMessage;
	/** URI generated for the insert. */
	protected FreenetURI generatedURI;
	// This could be a SimpleProgress, or it could be started/finished compression.
	// Not that important, so not saved on persistence.
	// Probably saving it would conflict with later changes (full persistence at
	// ClientPutter level).
	protected FCPMessage progressMessage;
	
	/** Whether to force an early generation of the CHK */
	protected final boolean earlyEncode;

	protected final FreenetURI publicURI;
	
	/** Metadata returned instead of URI */
	private Bucket generatedMetadata;

	public final static String SALT = "Salt";
	public final static String FILE_HASH = "FileHash";

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
	 * zero arg c'tor for db4o on jamvm
	 */
	protected ClientPutBase() {
		publicURI = null;
		getCHKOnly = false;
		earlyEncode = false;
		ctx = null;
	}

	public ClientPutBase(FreenetURI uri, String identifier, int verbosity, String charset, 
			FCPConnectionHandler handler, short priorityClass, short persistenceType, String clientToken, boolean global,
			boolean getCHKOnly, boolean dontCompress, boolean localRequestOnly, int maxRetries, boolean earlyEncode, boolean canWriteClientCache, boolean forkOnCacheable, String compressorDescriptor, int extraInsertsSingleBlock, int extraInsertsSplitfileHeader, boolean realTimeFlag, InsertContext.CompatibilityMode compatibilityMode, FCPServer server, ObjectContainer container) throws MalformedURLException {
		super(uri, identifier, verbosity, charset, handler, priorityClass, persistenceType, realTimeFlag, clientToken, global, container);
		this.getCHKOnly = getCHKOnly;
		ctx = new InsertContext(server.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
		ctx.canWriteClientCache = canWriteClientCache;
		ctx.compressorDescriptor = compressorDescriptor;
		ctx.forkOnCacheable = forkOnCacheable;
		ctx.extraInsertsSingleBlock = extraInsertsSingleBlock;
		ctx.extraInsertsSplitfileHeaderBlock = extraInsertsSplitfileHeader;
		ctx.setCompatibilityMode(compatibilityMode);
		ctx.localRequestOnly = localRequestOnly;
		this.earlyEncode = earlyEncode;
		publicURI = getPublicURI(this.uri);
	}

	static FreenetURI checkEmptySSK(FreenetURI uri, String filename, ClientContext context) {
		if("SSK".equals(uri.getKeyType()) && uri.getDocName() == null && uri.getRoutingKey() == null) {
			if(filename == null || filename.equals("")) filename = "key";
			// SSK@ = use a random SSK.
	    	InsertableClientSSK key = InsertableClientSSK.createRandom(context.random, "");
	    	return key.getInsertURI().setDocName(filename);
		} else {
			return uri;
		}
	}

	public ClientPutBase(FreenetURI uri, String identifier, int verbosity, String charset,
			FCPConnectionHandler handler, FCPClient client, short priorityClass, short persistenceType, String clientToken,
			boolean global, boolean getCHKOnly, boolean dontCompress, int maxRetries, boolean earlyEncode, boolean canWriteClientCache, boolean forkOnCacheable, boolean localRequestOnly, int extraInsertsSingleBlock, int extraInsertsSplitfileHeader, boolean realTimeFlag, String compressorDescriptor, InsertContext.CompatibilityMode compatMode, FCPServer server, ObjectContainer container) throws MalformedURLException {
		super(uri, identifier, verbosity, charset, handler, client, priorityClass, persistenceType, realTimeFlag, clientToken, global, container);
		this.getCHKOnly = getCHKOnly;
		ctx = new InsertContext(server.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
		ctx.canWriteClientCache = canWriteClientCache;
		ctx.compressorDescriptor = compressorDescriptor;
		ctx.forkOnCacheable = forkOnCacheable;
		ctx.extraInsertsSingleBlock = extraInsertsSingleBlock;
		ctx.extraInsertsSplitfileHeaderBlock = extraInsertsSplitfileHeader;
		ctx.localRequestOnly = localRequestOnly;
		ctx.setCompatibilityMode(compatMode);
		this.earlyEncode = earlyEncode;
		publicURI = getPublicURI(this.uri);
	}

	private FreenetURI getPublicURI(FreenetURI uri) throws MalformedURLException {
		String type = uri.getKeyType();
		if(type.equalsIgnoreCase("CHK")) {
			return uri;
		} else if(type.equalsIgnoreCase("SSK") || type.equalsIgnoreCase("USK")) {
			FreenetURI u = uri;
			if(type.equalsIgnoreCase("USK"))
				uri = uri.setKeyType("SSK");
			InsertableClientSSK issk = InsertableClientSSK.create(uri);
			uri = issk.getURI();
			if(type.equalsIgnoreCase("USK")) {
				uri = uri.setKeyType("USK");
				uri = uri.setSuggestedEdition(u.getSuggestedEdition());
			}
			// docName will be preserved.
			// Any meta strings *should not* be preserved.
			return uri;
		} else if(type.equalsIgnoreCase("KSK")) {
			return uri;
		} else {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public void onLostConnection(ObjectContainer container, ClientContext context) {
		if(persistenceType == PERSIST_CONNECTION)
			cancel(container, context);
		// otherwise ignore
	}

	@Override
	public void onSuccess(BaseClientPutter state, ObjectContainer container) {
		synchronized(this) {
			// Including this helps with certain bugs...
			//progressMessage = null;
			succeeded = true;
			finished = true;
			completionTime = System.currentTimeMillis();
			if(generatedURI == null)
				Logger.error(this, "No generated URI in onSuccess() for "+this+" from "+state);
		}
		// Could restart, and is on the putter, don't free data until we remove the putter
		//freeData(container);
		finish(container);
		trySendFinalMessage(null, container);
		if(client != null)
			client.notifySuccess(this, container);
	}

	@Override
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
		if(finished) return;
		synchronized(this) {
			finished = true;
			completionTime = System.currentTimeMillis();
			putFailedMessage = new PutFailedMessage(e, identifier, global);
		}
		if(persistenceType == PERSIST_FOREVER)
			container.store(this);
		// Could restart, and is on the putter, don't free data until we remove the putter
		//freeData(container);
		finish(container);
		trySendFinalMessage(null, container);
		if(client != null)
			client.notifyFailure(this, container);
	}

	@Override
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
		synchronized(this) {
			if(generatedURI != null) {
				if(!uri.equals(generatedURI))
					Logger.error(this, "onGeneratedURI("+uri+ ',' +state+") but already set generatedURI to "+generatedURI);
				else
					if(logMINOR) Logger.minor(this, "onGeneratedURI() twice with same value: "+generatedURI+" -> "+uri);
			} else {
				generatedURI = uri;
			}
		}
		if(persistenceType == PERSIST_FOREVER)
			container.store(this);
		trySendGeneratedURIMessage(null, container);
		if(client != null) {
			RequestStatusCache cache = client.getRequestStatusCache();
			if(cache != null) {
				FreenetURI u = uri;
				if(persistenceType == PERSIST_FOREVER) u = u.clone();
				cache.gotFinalURI(identifier, uri);
			}
		}
	}
	
	public FreenetURI getGeneratedURI(ObjectContainer container) {
		if(generatedURI == null) return null;
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(generatedURI, Integer.MAX_VALUE);
			FreenetURI ret = generatedURI.clone();
			container.deactivate(generatedURI, 1);
			return ret;
		} else
			return generatedURI;
	}
	
	@Override
	public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state,
			ObjectContainer container) {
		boolean delete = false;
		synchronized(this) {
			if(generatedURI != null)
				Logger.error(this, "Got generated metadata but already have URI on "+this+" from "+state);
			if(generatedMetadata != null) {
				Logger.error(this, "Already got generated metadata from "+state+" on "+this);
				delete = true;
			} else {
				generatedMetadata = metadata;
			}
		}
		if(delete) {
			metadata.free();
			metadata.removeFrom(container);
		} else {
			if(persistenceType == PERSIST_FOREVER) {
				metadata.storeTo(container);
				container.store(this);
			}
			trySendGeneratedMetadataMessage(metadata, null, container);
		}
	}
	
	@Override
	public void requestWasRemoved(ObjectContainer container, ClientContext context) {
		// if request is still running, send a PutFailed with code=cancelled
		if( !finished ) {
			synchronized(this) {
				finished = true;
				InsertException cancelled = new InsertException(InsertException.CANCELLED);
				putFailedMessage = new PutFailedMessage(cancelled, identifier, global);
			}
			trySendFinalMessage(null, container);
		}
		// notify client that request was removed
		FCPMessage msg = new PersistentRequestRemovedMessage(getIdentifier(), global);
		if(persistenceType == PERSIST_CONNECTION)
			origHandler.outputHandler.queue(msg);
		else
		client.queueClientRequestMessage(msg, 0, container);

		freeData(container);
		Bucket meta;
		synchronized(this) {
			meta = generatedMetadata;
			generatedMetadata = null;
		}
		// FIXME combine the synchronized blocks, null out even if non-persistent.
		if(meta != null) {
			meta.free();
			if(persistenceType == PERSIST_FOREVER) {
				meta.removeFrom(container);
			}
		}
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(ctx, 2);
			ctx.removeFrom(container);
			PutFailedMessage pfm;
			FreenetURI uri;
			FreenetURI pubURI;
			FCPMessage progress;
			synchronized(this) {
				pfm = putFailedMessage;
				putFailedMessage = null;
				uri = generatedURI;
				generatedURI = null;
				pubURI = publicURI;
				progress = progressMessage;
				progressMessage = null;
			}
			if(pfm != null) {
				container.activate(pfm, 5);
				pfm.removeFrom(container);
			}
			if(uri != null) {
				container.activate(uri, 5);
				uri.removeFrom(container);
			}
			if(progress != null) {
				container.activate(progress, 1);
				progress.removeFrom(container);
			}
			if(pubURI != null) {
				container.activate(pubURI, 5);
				pubURI.removeFrom(container);
			}
		}
		super.requestWasRemoved(container, context);
	}

	@Override
	public void receive(final ClientEvent ce, ObjectContainer container, ClientContext context) {
		if(finished) return;
		if(persistenceType == PERSIST_FOREVER && container == null) {
			try {
				context.jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						container.activate(ClientPutBase.this, 1);
						receive(ce, container, context);
						container.deactivate(ClientPutBase.this, 1);
						return false;
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				// Impossible, not much we can do.
			}
			return;
		}
		if(ce instanceof SplitfileProgressEvent) {
			if((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS) {
				SimpleProgressMessage progress = 
					new SimpleProgressMessage(identifier, global, (SplitfileProgressEvent)ce);
				lastActivity = System.currentTimeMillis();
				trySendProgressMessage(progress, VERBOSITY_SPLITFILE_PROGRESS, null, container, context);
			}
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.updateStatus(identifier, (SplitfileProgressEvent)ce);
				}
			}
		} else if(ce instanceof StartedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				StartedCompressionMessage msg =
					new StartedCompressionMessage(identifier, global, ((StartedCompressionEvent)ce).codec);
				trySendProgressMessage(msg, VERBOSITY_COMPRESSION_START_END, null, container, context);
				onStartCompressing();
			}
		} else if(ce instanceof FinishedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				FinishedCompressionMessage msg = 
					new FinishedCompressionMessage(identifier, global, (FinishedCompressionEvent)ce);
				trySendProgressMessage(msg, VERBOSITY_COMPRESSION_START_END, null, container, context);
				onStopCompressing();
			}
		} else if(ce instanceof ExpectedHashesEvent) {
			if((verbosity & VERBOSITY_EXPECTED_HASHES) == VERBOSITY_EXPECTED_HASHES) {
				ExpectedHashes msg =
					new ExpectedHashes((ExpectedHashesEvent)ce, identifier, global);
				trySendProgressMessage(msg, VERBOSITY_EXPECTED_HASHES, null, container, context);
				//FIXME: onHashesComputed();
			}
		}
	}

	protected abstract void onStopCompressing();

	protected abstract void onStartCompressing();

	@Override
	public void onFetchable(BaseClientPutter putter, ObjectContainer container) {
		if(finished) return;
		if((verbosity & VERBOSITY_PUT_FETCHABLE) == VERBOSITY_PUT_FETCHABLE) {
			FreenetURI temp;
			synchronized (this) {
				temp = generatedURI;
			}
			PutFetchableMessage msg =
				new PutFetchableMessage(identifier, global, temp);
			trySendProgressMessage(msg, VERBOSITY_PUT_FETCHABLE, null, container, null);
		}
	}

	private void trySendFinalMessage(FCPConnectionOutputHandler handler, ObjectContainer container) {

		FCPMessage msg;
		synchronized (this) {
			FreenetURI uri = generatedURI;
			if(persistenceType == PERSIST_FOREVER && uri != null) {
				container.activate(uri, 5);
				uri = uri.clone();
			}
			if(succeeded) {
				msg = new PutSuccessfulMessage(identifier, global, uri, startupTime, completionTime);
			} else {
				if(persistenceType == PERSIST_FOREVER)
					container.activate(putFailedMessage, 5);
				msg = putFailedMessage;
			}
		}

		if(msg == null) {
			Logger.error(this, "Trying to send null message on "+this, new Exception("error"));
		} else {
			if(persistenceType == PERSIST_CONNECTION && handler == null)
				handler = origHandler.outputHandler;
			if(handler != null)
				handler.queue(msg);
			else
				client.queueClientRequestMessage(msg, 0, container);
		}
	}

	private void trySendGeneratedURIMessage(FCPConnectionOutputHandler handler, ObjectContainer container) {
		FCPMessage msg;
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(client, 1);
			container.activate(generatedURI, 5);
		}
		synchronized(this) {
			msg = new URIGeneratedMessage(generatedURI, identifier, isGlobalQueue());
		}
		if(persistenceType == PERSIST_CONNECTION && handler == null)
			handler = origHandler.outputHandler;
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, 0, container);
	}

	/**
	 * @param metadata Activated by caller.
	 * @param handler
	 * @param container
	 */
	private void trySendGeneratedMetadataMessage(Bucket metadata, FCPConnectionOutputHandler handler, ObjectContainer container) {
		FCPMessage msg = new GeneratedMetadataMessage(identifier, global, metadata);
		if(persistenceType == PERSIST_CONNECTION && handler == null)
			handler = origHandler.outputHandler;
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, 0, container);
	}

	/**
	 * @param msg
	 * @param verbosity
	 * @param handler
	 * @param container Either container or context is required for a persistent request.
	 * @param context Can be null if container is not null.
	 */
	private void trySendProgressMessage(final FCPMessage msg, final int verbosity, FCPConnectionOutputHandler handler, ObjectContainer container, ClientContext context) {
		if(persistenceType == PERSIST_FOREVER) {
			if(container != null) {
				FCPMessage oldProgress = null;
				synchronized(this) {
					if(persistenceType != PERSIST_CONNECTION) {
						oldProgress = progressMessage;
						progressMessage = msg;
					}
				}
				if(oldProgress != null) {
					container.activate(oldProgress, 1);
					oldProgress.removeFrom(container);
				}
				container.store(this);
			} else {
				final FCPConnectionOutputHandler h = handler;
				try {
					context.jobRunner.queue(new DBJob() {

						@Override
						public boolean run(ObjectContainer container, ClientContext context) {
							container.activate(ClientPutBase.this, 1);
							trySendProgressMessage(msg, verbosity, h, container, context);
							container.deactivate(ClientPutBase.this, 1);
							return false;
						}
						
					}, NativeThread.NORM_PRIORITY, false);
				} catch (DatabaseDisabledException e) {
					// Impossible.
				}
				return;
			}
		} else {
			synchronized(this) {
				if(persistenceType != PERSIST_CONNECTION)
					progressMessage = msg;
			}
		}
		if(persistenceType == PERSIST_CONNECTION && handler == null)
			handler = origHandler.outputHandler;
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, verbosity, container);
	}

	@Override
	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData, ObjectContainer container) {
		if(includePersistentRequest) {
			FCPMessage msg = persistentTagMessage(container);
			handler.queue(msg);
		}

		boolean generated = false;
		FCPMessage msg = null;
		boolean fin = false;
		Bucket meta;
		synchronized (this) {
			generated = generatedURI != null;
			msg = progressMessage;
			fin = finished;
			meta = generatedMetadata;
		}
		if(persistenceType == PERSIST_FOREVER && msg != null)
			container.activate(msg, 5);
		if(generated)
			trySendGeneratedURIMessage(handler, container);
		if(meta != null)
			trySendGeneratedMetadataMessage(meta, handler, container);
		if(msg != null)
			handler.queue(msg);
		if(fin)
			trySendFinalMessage(handler, container);
	}

	protected abstract String getTypeName();

	@Override
	public synchronized double getSuccessFraction(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressMessage != null)
			container.activate(progressMessage, 2);
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getFraction();
			else return 0;
		} else
			return -1;
	}


	@Override
	public synchronized double getTotalBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressMessage != null)
			container.activate(progressMessage, 2);
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getTotalBlocks();
			else return 0;
		} else
			return -1;
	}

	@Override
	public synchronized double getMinBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressMessage != null)
			container.activate(progressMessage, 2);
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getMinBlocks();
			else return 0;
		} else
			return -1;
	}

	@Override
	public synchronized double getFailedBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressMessage != null)
			container.activate(progressMessage, 2);
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getFailedBlocks();
			else return 0;
		} else
			return -1;
	}

	@Override
	public synchronized double getFatalyFailedBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressMessage != null)
			container.activate(progressMessage, 2);
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getFatalyFailedBlocks();
			else return 0;
		} else
			return -1;
	}

	@Override
	public synchronized double getFetchedBlocks(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && progressMessage != null)
			container.activate(progressMessage, 2);
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getFetchedBlocks();
			else return 0;
		} else
			return -1;
	}

	@Override
	public synchronized boolean isTotalFinalized(ObjectContainer container) {
		if(!(progressMessage instanceof SimpleProgressMessage)) return false;
		else {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(progressMessage, 5);
			return ((SimpleProgressMessage)progressMessage).isTotalFinalized();
		}
	}

	@Override
	public synchronized String getFailureReason(boolean longDescription, ObjectContainer container) {
		if(putFailedMessage == null)
			return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(putFailedMessage, 5);
		String s = putFailedMessage.shortCodeDescription;
		if(longDescription && putFailedMessage.extraDescription != null)
			s += ": "+putFailedMessage.extraDescription;
		return s;
	}

	public PutFailedMessage getFailureMessage(ObjectContainer container) {
		if(putFailedMessage == null)
			return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(putFailedMessage, 5);
		return putFailedMessage;
	}
	
	public void setVarsRestart(ObjectContainer container) {
		PutFailedMessage pfm;
		FCPMessage progress;
		synchronized(this) {
			finished = false;
			pfm = putFailedMessage;
			progress = progressMessage;
			this.putFailedMessage = null;
			this.progressMessage = null;
			started = false;
		}
		if(persistenceType == PERSIST_FOREVER) {
			if(pfm != null) {
				container.activate(pfm, 1);
				pfm.removeFrom(container);
			}
			if(progress != null) {
				container.activate(progress, 1);
				progress.removeFrom(container);
			}
			container.store(this);
		}
	}

}
