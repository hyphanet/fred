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
import freenet.client.events.SimpleEventProducer;
import freenet.client.events.SplitfileProgressEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
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
	private static final int VERBOSITY_PUT_FETCHABLE = 256;
	private static final int VERBOSITY_COMPRESSION_START_END = 512;

	// Stuff waiting for reconnection
	/** Has the request succeeded? */
	protected boolean succeeded;
	/** If the request failed, how did it fail? PutFailedMessage is the most
	 * convenient way to store this (InsertException has a stack trace!).
	 */
	private PutFailedMessage putFailedMessage;
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

	public final static String SALT = "Salt";
	public final static String FILE_HASH = "FileHash";

	public ClientPutBase(FreenetURI uri, String identifier, int verbosity, String charset, 
			FCPConnectionHandler handler, short priorityClass, short persistenceType, String clientToken, boolean global,
			boolean getCHKOnly, boolean dontCompress, boolean localRequestOnly, int maxRetries, boolean earlyEncode, boolean canWriteClientCache, boolean forkOnCacheable, String compressorDescriptor, int extraInsertsSingleBlock, int extraInsertsSplitfileHeader, InsertContext.CompatibilityMode compatibilityMode, String filename, FCPServer server, ObjectContainer container) throws MalformedURLException {
		super(checkEmptySSK(uri, filename, server.core.clientContext), identifier, verbosity, charset, handler, priorityClass, persistenceType, clientToken, global, container);
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
			// SSK@ = use a random SSK.
	    	InsertableClientSSK key = InsertableClientSSK.createRandom(context.random, "");
	    	return key.getInsertURI().setDocName(filename);
		} else {
			return uri;
		}
	}

	public ClientPutBase(FreenetURI uri, String identifier, int verbosity, String charset,
			FCPConnectionHandler handler, FCPClient client, short priorityClass, short persistenceType, String clientToken,
			boolean global, boolean getCHKOnly, boolean dontCompress, int maxRetries, boolean earlyEncode, boolean canWriteClientCache, boolean forkOnCacheable, boolean localRequestOnly, int extraInsertsSingleBlock, int extraInsertsSplitfileHeader, String compressorDescriptor, InsertContext.CompatibilityMode compatMode, FCPServer server, ObjectContainer container) throws MalformedURLException {
		super(uri, identifier, verbosity, charset, handler, client, priorityClass, persistenceType, clientToken, global, container);
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

	public ClientPutBase(SimpleFieldSet fs, FCPClient client2, FCPServer server) throws MalformedURLException {
		super(fs, client2);
		publicURI = getPublicURI(this.uri);
		getCHKOnly = Fields.stringToBool(fs.get("CHKOnly"), false);
		boolean dontCompress = Fields.stringToBool(fs.get("DontCompress"), false);
		int maxRetries = Integer.parseInt(fs.get("MaxRetries"));
		clientToken = fs.get("ClientToken");
		finished = Fields.stringToBool(fs.get("Finished"), false);
		//finished = false;
		succeeded = Fields.stringToBool(fs.get("Succeeded"), false);
		ctx = new InsertContext(server.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
		ctx.compressorDescriptor = fs.get("Codecs");
		String genURI = fs.get("GeneratedURI");
		if(genURI != null)
			generatedURI = new FreenetURI(genURI);
		if(finished) {
			String ctime = fs.get("CompletionTime");
			if(ctime != null)
				completionTime = Long.parseLong(ctime);
			if(!succeeded)
				putFailedMessage = new PutFailedMessage(fs.subset("PutFailed"), false);
		}
		earlyEncode = Fields.stringToBool(fs.get("EarlyEncode"), false);
		if(fs.get("ForkOnCacheable") != null)
			ctx.forkOnCacheable = fs.getBoolean("ForkOnCacheable", false);
		else
			ctx.forkOnCacheable = Node.FORK_ON_CACHEABLE_DEFAULT;
	}

	private FreenetURI getPublicURI(FreenetURI uri) throws MalformedURLException {
		String type = uri.getKeyType();
		if(type.equalsIgnoreCase("CHK")) {
			return uri;
		} else if(type.equalsIgnoreCase("SSK") || type.equalsIgnoreCase("USK")) {
			if(type.equalsIgnoreCase("USK"))
				uri = uri.setKeyType("SSK");
			InsertableClientSSK issk = InsertableClientSSK.create(uri);
			uri = uri.setRoutingKey(issk.getURI().getRoutingKey());
			uri = uri.setKeyType(type);
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

	public void onSuccess(BaseClientPutter state, ObjectContainer container) {
		synchronized(this) {
			// Including this helps with certain bugs...
			//progressMessage = null;
			succeeded = true;
			finished = true;
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

	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
		if(finished) return;
		synchronized(this) {
			finished = true;
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

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
		synchronized(this) {
			if(generatedURI != null) {
				if(!uri.equals(generatedURI))
					Logger.error(this, "onGeneratedURI("+uri+ ',' +state+") but already set generatedURI to "+generatedURI);
				else
					if(Logger.shouldLog(LogLevel.MINOR, this)) Logger.minor(this, "onGeneratedURI() twice with same value: "+generatedURI+" -> "+uri);
			} else {
				generatedURI = uri;
			}
		}
		if(persistenceType == PERSIST_FOREVER)
			container.store(this);
		trySendGeneratedURIMessage(null, container);
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

	public void receive(final ClientEvent ce, ObjectContainer container, ClientContext context) {
		if(finished) return;
		if(persistenceType == PERSIST_FOREVER && container == null) {
			try {
				context.jobRunner.queue(new DBJob() {

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
		}
	}

	protected abstract void onStopCompressing();

	protected abstract void onStartCompressing();

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
		if(persistenceType == PERSIST_CONNECTION) {
			Logger.error(this, "WTF? persistenceType="+persistenceType, new Exception("error"));
			return;
		}
		if(includePersistentRequest) {
			FCPMessage msg = persistentTagMessage(container);
			handler.queue(msg);
		}

		boolean generated = false;
		FCPMessage msg = null;
		boolean fin = false;
		synchronized (this) {
			generated = generatedURI != null;
			msg = progressMessage;
			fin = finished;
		}
		if(persistenceType == PERSIST_FOREVER && msg != null)
			container.activate(msg, 5);
		if(generated)
			trySendGeneratedURIMessage(handler, container);
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
		if(pfm != null) {
			container.activate(pfm, 1);
			pfm.removeFrom(container);
		}
		if(progress != null) {
			container.activate(progress, 1);
			progress.removeFrom(container);
		}
		if(persistenceType == PERSIST_FOREVER)
			container.store(this);
	}
	
}
