package freenet.node.fcp;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.*;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.SimpleEventProducer;
import freenet.client.events.SplitfileProgressEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

/**
 * Base class for ClientPut and ClientPutDir.
 * Any code which can be shared between the two goes here.
 */
public abstract class ClientPutBase extends ClientRequest implements ClientCallback, ClientEventListener {

	/** Created new for each ClientPutBase, so we have to delete it in requestWasRemoved() */
	final InsertContext ctx;
	final boolean getCHKOnly;

	// Verbosity bitmasks
	private int VERBOSITY_SPLITFILE_PROGRESS = 1;
	private int VERBOSITY_PUT_FETCHABLE = 256;
	private int VERBOSITY_COMPRESSION_START_END = 512;

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
	private FCPMessage progressMessage;

	/** Whether to force an early generation of the CHK */
	protected final boolean earlyEncode;

	protected final FreenetURI publicURI;

	public final static String SALT = "Salt";
	public final static String FILE_HASH = "FileHash";

	public ClientPutBase(FreenetURI uri, String identifier, int verbosity, FCPConnectionHandler handler, 
			short priorityClass, short persistenceType, String clientToken, boolean global, boolean getCHKOnly,
			boolean dontCompress, int maxRetries, boolean earlyEncode, FCPServer server) throws MalformedURLException {
		super(uri, identifier, verbosity, handler, priorityClass, persistenceType, clientToken, global);
		this.getCHKOnly = getCHKOnly;
		ctx = new InsertContext(server.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
		this.earlyEncode = earlyEncode;
		publicURI = getPublicURI(uri);
	}

	public ClientPutBase(FreenetURI uri, String identifier, int verbosity, FCPConnectionHandler handler,
			FCPClient client, short priorityClass, short persistenceType, String clientToken, boolean global,
			boolean getCHKOnly, boolean dontCompress, int maxRetries, boolean earlyEncode, FCPServer server) throws MalformedURLException {
		super(uri, identifier, verbosity, handler, client, priorityClass, persistenceType, clientToken, global);
		this.getCHKOnly = getCHKOnly;
		ctx = new InsertContext(server.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
		this.earlyEncode = earlyEncode;
		publicURI = getPublicURI(uri);
	}

	public ClientPutBase(SimpleFieldSet fs, FCPClient client2, FCPServer server) throws MalformedURLException {
		super(fs, client2);
		publicURI = getPublicURI(uri);
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
		}
		freeData(container);
		finish(container);
		trySendFinalMessage(null, container);
		client.notifySuccess(this, container);
	}

	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
		if(finished) return;
		synchronized(this) {
			finished = true;
			putFailedMessage = new PutFailedMessage(e, identifier, global);
		}
		freeData(container);
		finish(container);
		trySendFinalMessage(null, container);
		client.notifyFailure(this, container);
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
		synchronized(this) {
			if((generatedURI != null) && !uri.equals(generatedURI))
				Logger.error(this, "onGeneratedURI("+uri+ ',' +state+") but already set generatedURI to "+generatedURI);
			generatedURI = uri;
		}
		if(persistenceType == PERSIST_FOREVER)
			container.set(this);
		trySendGeneratedURIMessage(null, container);
	}

	public void requestWasRemoved(ObjectContainer container) {
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
		client.queueClientRequestMessage(msg, 0, container);

		freeData(container);
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(ctx, 2);
			ctx.removeFrom(container);
			PutFailedMessage pfm;
			FreenetURI uri;
			FCPMessage progress;
			synchronized(this) {
				pfm = putFailedMessage;
				putFailedMessage = null;
				uri = generatedURI;
				generatedURI = null;
				progress = progressMessage;
				progressMessage = null;
			}
			if(pfm != null)
				pfm.removeFrom(container);
			if(uri != null)
				uri.removeFrom(container);
			if(progress != null)
				progress.removeFrom(container);
			publicURI.removeFrom(container);
		}
	}

	public void receive(final ClientEvent ce, ObjectContainer container, ClientContext context) {
		if(finished) return;
		if(persistenceType == PERSIST_FOREVER && container == null) {
			context.jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(ClientPutBase.this, 1);
					receive(ce, container, context);
					container.deactivate(ClientPutBase.this, 1);
				}
				
			}, NativeThread.NORM_PRIORITY, false);
			return;
		}
		if(ce instanceof SplitfileProgressEvent) {
			if((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS) {
				SimpleProgressMessage progress = 
					new SimpleProgressMessage(identifier, global, (SplitfileProgressEvent)ce);
				trySendProgressMessage(progress, VERBOSITY_SPLITFILE_PROGRESS, null, container, context);
			}
		} else if(ce instanceof StartedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				StartedCompressionMessage msg =
					new StartedCompressionMessage(identifier, global, ((StartedCompressionEvent)ce).codec);
				trySendProgressMessage(msg, VERBOSITY_COMPRESSION_START_END, null, container, context);
			}
		} else if(ce instanceof FinishedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				FinishedCompressionMessage msg = 
					new FinishedCompressionMessage(identifier, global, (FinishedCompressionEvent)ce);
				trySendProgressMessage(msg, VERBOSITY_COMPRESSION_START_END, null, container, context);
			}
		}
	}

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
			if(succeeded) {
				msg = new PutSuccessfulMessage(identifier, global, generatedURI, startupTime, completionTime);
			} else {
				msg = putFailedMessage;
			}
		}

		if(msg == null) {
			Logger.error(this, "Trying to send null message on "+this, new Exception("error"));
		} else {
			if(handler != null)
				handler.queue(msg);
			else
				client.queueClientRequestMessage(msg, 0, container);
		}
	}

	private void trySendGeneratedURIMessage(FCPConnectionOutputHandler handler, ObjectContainer container) {
		FCPMessage msg;
		synchronized(this) {
			msg = new URIGeneratedMessage(generatedURI, identifier);
		}
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
	private void trySendProgressMessage(FCPMessage msg, int verbosity, FCPConnectionOutputHandler handler, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(persistenceType != PERSIST_CONNECTION)
				progressMessage = msg;
		}
		if(persistenceType == PERSIST_FOREVER) {
			if(container != null) {
				container.set(this);
			} else {
				context.jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						container.set(ClientPutBase.this);
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			}
		}
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, verbosity, container);
	}

	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData, ObjectContainer container) {
		if(persistenceType == PERSIST_CONNECTION) {
			Logger.error(this, "WTF? persistenceType="+persistenceType, new Exception("error"));
			return;
		}
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(this, 2);
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
		if(generated)
			trySendGeneratedURIMessage(handler, container);
		if(msg != null)
			handler.queue(msg);
		if(fin)
			trySendFinalMessage(handler, container);
	}

	public synchronized SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false); // we will need multi-level later...
		fs.putSingle("Type", getTypeName());
		fs.putSingle("URI", uri.toString(false, false));
		fs.putSingle("Identifier", identifier);
		fs.putSingle("Verbosity", Integer.toString(verbosity));
		fs.putSingle("PriorityClass", Short.toString(priorityClass));
		fs.putSingle("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		fs.putSingle("ClientName", client.name);
		fs.putSingle("ClientToken", clientToken);
		fs.putSingle("DontCompress", Boolean.toString(ctx.dontCompress));
		fs.putSingle("MaxRetries", Integer.toString(ctx.maxInsertRetries));
		fs.putSingle("Finished", Boolean.toString(finished));
		fs.putSingle("Succeeded", Boolean.toString(succeeded));
		fs.putSingle("GetCHKOnly", Boolean.toString(getCHKOnly));
		if(generatedURI != null)
			fs.putSingle("GeneratedURI", generatedURI.toString(false, false));
		if(finished && (!succeeded))
			// Should have a putFailedMessage... unless there is a race condition.
			fs.put("PutFailed", putFailedMessage.getFieldSet(false));
		fs.putSingle("Global", Boolean.toString(client.isGlobalQueue));
		fs.put("StartupTime", startupTime);
		if(finished)
			fs.put("CompletionTime", completionTime);
		
		return fs;
	}

	protected abstract String getTypeName();

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

	public synchronized boolean isTotalFinalized(ObjectContainer container) {
		if(!(progressMessage instanceof SimpleProgressMessage)) return false;
		else {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(putFailedMessage, 5);
			return ((SimpleProgressMessage)progressMessage).isTotalFinalized();
		}
	}

	public synchronized String getFailureReason(ObjectContainer container) {
		if(putFailedMessage == null)
			return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(putFailedMessage, 5);
		String s = putFailedMessage.shortCodeDescription;
		if(putFailedMessage.extraDescription != null)
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
		pfm.removeFrom(container);
		progress.removeFrom(container);
		if(persistenceType == PERSIST_FOREVER)
			container.set(this);
	}
}
