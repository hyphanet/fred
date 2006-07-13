package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.SimpleEventProducer;
import freenet.client.events.SplitfileProgressEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.FreenetURI;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Base class for ClientPut and ClientPutDir.
 * Any code which can be shared between the two goes here.
 */
public abstract class ClientPutBase extends ClientRequest implements ClientCallback, ClientEventListener {

	final InserterContext ctx;
	final boolean getCHKOnly;

	// Verbosity bitmasks
	private int VERBOSITY_SPLITFILE_PROGRESS = 1;
	private int VERBOSITY_COMPRESSION_START_END = 512;
	
	// Stuff waiting for reconnection
	/** Has the request succeeded? */
	protected boolean succeeded;
	/** If the request failed, how did it fail? PutFailedMessage is the most
	 * convenient way to store this (InserterException has a stack trace!).
	 */
	private PutFailedMessage putFailedMessage;
	/** URI generated for the insert. */
	protected FreenetURI generatedURI;
	// This could be a SimpleProgress, or it could be started/finished compression.
	// Not that important, so not saved on persistence.
	// Probably saving it would conflict with later changes (full persistence at
	// ClientPutter level).
	private FCPMessage progressMessage;
	
	public ClientPutBase(FreenetURI uri, String identifier, int verbosity, FCPConnectionHandler handler, 
			short priorityClass, short persistenceType, String clientToken, boolean global, boolean getCHKOnly,
			boolean dontCompress, int maxRetries) {
		super(uri, identifier, verbosity, handler, priorityClass, persistenceType, clientToken, global);
		this.getCHKOnly = getCHKOnly;
		ctx = new InserterContext(client.defaultInsertContext, new SimpleEventProducer(), persistenceType == ClientRequest.PERSIST_CONNECTION);
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
	}

	public ClientPutBase(SimpleFieldSet fs, FCPClient client2) throws MalformedURLException {
		super(fs, client2);
		getCHKOnly = Fields.stringToBool(fs.get("CHKOnly"), false);
		boolean dontCompress = Fields.stringToBool(fs.get("DontCompress"), false);
		int maxRetries = Integer.parseInt(fs.get("MaxRetries"));
		clientToken = fs.get("ClientToken");
		finished = Fields.stringToBool(fs.get("Finished"), false);
		//finished = false;
		succeeded = Fields.stringToBool(fs.get("Succeeded"), false);
		ctx = new InserterContext(client.defaultInsertContext, new SimpleEventProducer());
		ctx.dontCompress = dontCompress;
		ctx.eventProducer.addEventListener(this);
		ctx.maxInsertRetries = maxRetries;
		String genURI = fs.get("GeneratedURI");
		if(genURI != null)
			generatedURI = new FreenetURI(genURI);
		if(finished && (!succeeded))
			putFailedMessage = new PutFailedMessage(fs.subset("PutFailed"), false);
	}

	public void onLostConnection() {
		if(persistenceType == PERSIST_CONNECTION)
			cancel();
		// otherwise ignore
	}
	
	public void onSuccess(BaseClientPutter state) {
		synchronized(this) {
			progressMessage = null;
			succeeded = true;
			finished = true;
		}
		trySendFinalMessage(null);
		freeData();
		finish();
		if(persistenceType != PERSIST_CONNECTION)
			client.server.forceStorePersistentRequests();
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		synchronized(this) {
			finished = true;
			putFailedMessage = new PutFailedMessage(e, identifier);
		}
		trySendFinalMessage(null);
		freeData();
		finish();
		if(persistenceType != PERSIST_CONNECTION)
			client.server.forceStorePersistentRequests();
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		synchronized(this) {
			if((generatedURI != null) && !uri.equals(generatedURI))
				Logger.error(this, "onGeneratedURI("+uri+","+state+") but already set generatedURI to "+generatedURI);
			generatedURI = uri;
		}
		trySendGeneratedURIMessage(null);
	}

	public void onSuccess(FetchResult result, ClientGetter state){}

	public void onFailure(FetchException e, ClientGetter state){}

	public void receive(ClientEvent ce) {
		if(finished) return;
		if(ce instanceof SplitfileProgressEvent) {
			if((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS) {
				SimpleProgressMessage progress = 
					new SimpleProgressMessage(identifier, (SplitfileProgressEvent)ce);
				trySendProgressMessage(progress, VERBOSITY_SPLITFILE_PROGRESS, null);
			}
		} else if(ce instanceof StartedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				StartedCompressionMessage msg =
					new StartedCompressionMessage(identifier, ((StartedCompressionEvent)ce).codec);
				trySendProgressMessage(msg, VERBOSITY_COMPRESSION_START_END, null);
			}
		} else if(ce instanceof FinishedCompressionEvent) {
			if((verbosity & VERBOSITY_COMPRESSION_START_END) == VERBOSITY_COMPRESSION_START_END) {
				FinishedCompressionMessage msg = 
					new FinishedCompressionMessage(identifier, (FinishedCompressionEvent)ce);
				trySendProgressMessage(msg, VERBOSITY_COMPRESSION_START_END, null);
			}
		}
	}
	
	private void trySendFinalMessage(FCPConnectionOutputHandler handler) {
		
		FCPMessage msg;
		synchronized (this) {
			if(succeeded) {
				msg = new PutSuccessfulMessage(identifier, generatedURI);
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
				client.queueClientRequestMessage(msg, 0);
		}
	}

	private void trySendGeneratedURIMessage(FCPConnectionOutputHandler handler) {
		FCPMessage msg;
		synchronized(this) {
			msg = new URIGeneratedMessage(generatedURI, identifier);
		}
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, 0);
	}

	private void trySendProgressMessage(FCPMessage msg, int verbosity, FCPConnectionOutputHandler handler) {
		synchronized(this) {
			if(persistenceType != PERSIST_CONNECTION)
				progressMessage = msg;
		}
		if(handler != null)
			handler.queue(msg);
		else
			client.queueClientRequestMessage(msg, verbosity);
	}
	
	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData) {
		if(persistenceType == PERSIST_CONNECTION) {
			Logger.error(this, "WTF? persistenceType="+persistenceType, new Exception("error"));
			return;
		}
		if(includePersistentRequest) {
			FCPMessage msg = persistentTagMessage();
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
			trySendGeneratedURIMessage(handler);
		if(msg != null)
			handler.queue(msg);
		if(fin)
			trySendFinalMessage(handler);
	}

	protected abstract FCPMessage persistentTagMessage();
	
	public synchronized SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true); // we will need multi-level later...
		fs.put("Type", getTypeName());
		fs.put("URI", uri.toString(false));
		fs.put("Identifier", identifier);
		fs.put("Verbosity", Integer.toString(verbosity));
		fs.put("PriorityClass", Short.toString(priorityClass));
		fs.put("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		fs.put("ClientName", client.name);
		fs.put("ClientToken", clientToken);
		fs.put("DontCompress", Boolean.toString(ctx.dontCompress));
		fs.put("MaxRetries", Integer.toString(ctx.maxInsertRetries));
		fs.put("Finished", Boolean.toString(finished));
		fs.put("Succeeded", Boolean.toString(succeeded));
		fs.put("GetCHKOnly", Boolean.toString(getCHKOnly));
		if(generatedURI != null)
			fs.put("GeneratedURI", generatedURI.toString(false));
		if(finished && (!succeeded))
			// Should have a putFailedMessage... unless there is a race condition.
			fs.put("PutFailed", putFailedMessage.getFieldSet(false));
		fs.put("Global", Boolean.toString(client.isGlobalQueue));
		return fs;
	}
	
	protected abstract String getTypeName();

	public synchronized double getSuccessFraction() {
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getFraction();
			else return 0;
		} else
			return -1;
	}

	
	public synchronized double getTotalBlocks() {
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getTotalBlocks();
			else return 0;
		} else
			return -1;
	}
	
	public synchronized double getMinBlocks() {
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getMinBlocks();
			else return 0;
		} else
			return -1;
	}
	
	public synchronized double getFailedBlocks() {
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getFailedBlocks();
			else return 0;
		} else
			return -1;
	}
	
	public synchronized double getFatalyFailedBlocks() {
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getFatalyFailedBlocks();
			else return 0;
		} else
			return -1;
	}
	
	public synchronized double getFetchedBlocks() {
		if(progressMessage != null) {
			if(progressMessage instanceof SimpleProgressMessage)
				return ((SimpleProgressMessage)progressMessage).getFetchedBlocks();
			else return 0;
		} else
			return -1;
	}
	
	public synchronized boolean isTotalFinalized() {
		if(!(progressMessage instanceof SimpleProgressMessage)) return false;
		else return ((SimpleProgressMessage)progressMessage).isTotalFinalized();
	}
	
	public synchronized String getFailureReason() {
		if(putFailedMessage == null)
			return null;
		String s = putFailedMessage.shortCodeDescription;
		if(putFailedMessage.extraDescription != null)
			s += ": "+putFailedMessage.extraDescription;
		return s;
	}
	
}
