package freenet.node.fcp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import freenet.client.FetchContext;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertContext;
import freenet.node.NodeClientCore;
import freenet.support.Logger;

/**
 * An FCP client.
 * Identified by its Name which is sent on connection.
 */
public class FCPClient {
	
	public FCPClient(String name2, FCPServer server, FCPConnectionHandler handler, boolean isGlobalQueue, RequestCompletionCallback cb) {
		this.name = name2;
		if(name == null) throw new NullPointerException();
		this.currentConnection = handler;
		this.runningPersistentRequests = new HashSet<ClientRequest>();
		this.completedUnackedRequests = new Vector<ClientRequest>();
		this.clientRequestsByIdentifier = new HashMap<String, ClientRequest>();
		this.server = server;
		this.core = server.core;
		this.client = core.makeClient((short)0);
		this.isGlobalQueue = isGlobalQueue;
		defaultFetchContext = client.getFetchContext();
		defaultInsertContext = client.getInsertContext(false);
		clientsWatching = new LinkedList<FCPClient>();
		watchGlobalVerbosityMask = Integer.MAX_VALUE;
		toStart = new LinkedList<ClientRequest>();
		lowLevelClient = this;
		completionCallback = cb;
	}
	
	/** The client's Name sent in the ClientHello message */
	final String name;
	/** The FCPServer */
	final FCPServer server;
	/** The current connection handler, if any. */
	private FCPConnectionHandler currentConnection;
	/** Currently running persistent requests */
	private final HashSet<ClientRequest> runningPersistentRequests;
	/** Completed unacknowledged persistent requests */
	private final Vector<ClientRequest> completedUnackedRequests;
	/** ClientRequest's by identifier */
	private final HashMap<String, ClientRequest> clientRequestsByIdentifier;
	/** Client (one FCPClient = one HighLevelSimpleClient = one round-robin slot) */
	private final HighLevelSimpleClient client;
	public final FetchContext defaultFetchContext;
	public final InsertContext defaultInsertContext;
	public final NodeClientCore core;
	/** Are we the global queue? */
	public final boolean isGlobalQueue;
	/** Are we watching the global queue? */
	boolean watchGlobal;
	int watchGlobalVerbosityMask;
	/** FCPClients watching us */
	// FIXME how do we lazily init this without synchronization problems?
	// We obviously can't synchronize on it when it hasn't been constructed yet...
	final LinkedList<FCPClient> clientsWatching;
	private final LinkedList<ClientRequest> toStart;
	/** Low-level client object, for freenet.client.async. Normally == this. */
	final Object lowLevelClient;
	private RequestCompletionCallback completionCallback;
	
	public synchronized FCPConnectionHandler getConnection() {
		return currentConnection;
	}
	
	public synchronized void setConnection(FCPConnectionHandler handler) {
		this.currentConnection = handler;
	}

	public synchronized void onLostConnection(FCPConnectionHandler handler) {
		handler.freeDDAJobs();
		if(currentConnection == handler)
			currentConnection = null;
	}

	/**
	 * Called when a client request has finished, but is persistent. It has not been
	 * acked yet, so it should be moved to the unacked-completed-requests set.
	 */
	public void finishedClientRequest(ClientRequest get) {
		synchronized(this) {
			if(runningPersistentRequests.remove(get)) {
				completedUnackedRequests.add(get);
			}	
		}
		if(get.isPersistentForever()) {
			server.forceStorePersistentRequests();
		}
	}

	/**
	 * Queue any and all pending messages from already completed, unacknowledged, persistent
	 * requests, to be immediately sent. This happens automatically on startup and hopefully
	 * will encourage clients to acknowledge persistent requests!
	 */
	public void queuePendingMessagesOnConnectionRestart(FCPConnectionOutputHandler outputHandler) {
		Object[] reqs;
		synchronized(this) {
			reqs = completedUnackedRequests.toArray();
		}
		for(int i=0;i<reqs.length;i++)
			((ClientRequest)reqs[i]).sendPendingMessages(outputHandler, true, false, false);
	}
	
	/**
	 * Queue any and all pending messages from running requests. Happens on demand.
	 */
	public void queuePendingMessagesFromRunningRequests(FCPConnectionOutputHandler outputHandler) {
		Object[] reqs;
		synchronized(this) {
			reqs = runningPersistentRequests.toArray();
		}
		for(int i=0;i<reqs.length;i++)
			((ClientRequest)reqs[i]).sendPendingMessages(outputHandler, true, false, false);
	}
	
	public void register(ClientRequest cg, boolean startLater) throws IdentifierCollisionException {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Registering "+cg.getIdentifier()+(startLater ? " to start later" : ""));
		synchronized(this) {
			String ident = cg.getIdentifier();
			ClientRequest old = clientRequestsByIdentifier.get(ident);
			if((old != null) && (old != cg))
				throw new IdentifierCollisionException();
			if(cg.hasFinished()) {
				completedUnackedRequests.add(cg);
			} else {
				runningPersistentRequests.add(cg);
				if(startLater) toStart.add(cg);
			}
			clientRequestsByIdentifier.put(ident, cg);
		}
	}

	public void removeByIdentifier(String identifier, boolean kill) throws MessageInvalidException {
		ClientRequest req;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "removeByIdentifier("+identifier+ ',' +kill+ ')');
		synchronized(this) {
			req = clientRequestsByIdentifier.get(identifier);
			if(req == null)
				throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, "Not in hash", identifier, isGlobalQueue);
			else if(!(runningPersistentRequests.remove(req) || completedUnackedRequests.remove(req)))
				throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, "Not found", identifier, isGlobalQueue);
			clientRequestsByIdentifier.remove(identifier);
		}
        req.requestWasRemoved();
		if(kill) {
			if(logMINOR) Logger.minor(this, "Killing request "+req);
			req.cancel();
		}
		if(completionCallback != null)
			completionCallback.onRemove(req);
		server.forceStorePersistentRequests();
	}

	public boolean hasPersistentRequests() {
		return !(runningPersistentRequests.isEmpty() && completedUnackedRequests.isEmpty());
	}

	public void addPersistentRequests(List<ClientRequest> v, boolean onlyForever) {
		synchronized(this) {
			Iterator<ClientRequest> i = runningPersistentRequests.iterator();
			while(i.hasNext()) {
				ClientRequest req = i.next();
				if(req.isPersistentForever() || !onlyForever)
					v.add(req);
			}
			v.addAll(completedUnackedRequests);
		}
	}

	/**
	 * Enable or disable watch-the-global-queue.
	 * @param enabled Whether we want watch-global-queue to be enabled.
	 * @param verbosityMask If so, what verbosity mask to use (to filter messages
	 * generated by the global queue).
	 */
	public void setWatchGlobal(boolean enabled, int verbosityMask) {
		if(isGlobalQueue) {
			Logger.error(this, "Set watch global on global queue!");
			return;
		}
		if(watchGlobal && !enabled) {
			server.globalClient.unwatch(this);
			watchGlobal = false;
		} else if(enabled && !watchGlobal) {
			server.globalClient.watch(this);
			FCPConnectionHandler connHandler = getConnection();
			if(connHandler != null) {
				server.globalClient.queuePendingMessagesOnConnectionRestart(connHandler.outputHandler);
			}
			watchGlobal = true;
		}
		// Otherwise the status is unchanged.
		this.watchGlobalVerbosityMask = verbosityMask;
	}

	public void queueClientRequestMessage(FCPMessage msg, int verbosityLevel) {
		if((verbosityLevel & watchGlobalVerbosityMask) != verbosityLevel)
			return;
		FCPConnectionHandler conn = getConnection();
		if(conn != null) {
			conn.outputHandler.queue(msg);
		}
		FCPClient[] clients;
		if(isGlobalQueue) {
			synchronized(clientsWatching) {
				clients = clientsWatching.toArray(new FCPClient[clientsWatching.size()]);
			}
			for(int i=0;i<clients.length;i++)
				clients[i].queueClientRequestMessage(msg, verbosityLevel);
		}
	}
	
	private void unwatch(FCPClient client) {
		if(!isGlobalQueue) return;
		synchronized(clientsWatching) {
			clientsWatching.remove(client);
		}
	}

	private void watch(FCPClient client) {
		if(!isGlobalQueue) return;
		synchronized(clientsWatching) {
			clientsWatching.add(client);
		}
	}

	public synchronized ClientRequest getRequest(String identifier) {
		return clientRequestsByIdentifier.get(identifier);
	}

	/**
	 * Start all delayed-start requests.
	 */
	public void finishStart() {
		ClientRequest[] reqs;
		synchronized(this) {
			reqs = toStart.toArray(new ClientRequest[toStart.size()]);
		}
		for(int i=0;i<reqs.length;i++)
			reqs[i].start();
	}
	
	@Override
	public String toString() {
		return super.toString()+ ':' +name;
	}

	/**
	 * Callback called when a request succeeds.
	 */
	public void notifySuccess(ClientRequest req) {
		if(completionCallback != null)
			completionCallback.notifySuccess(req);
	}

	/**
	 * Callback called when a request fails
	 * @param get
	 */
	public void notifyFailure(ClientRequest req) {
		if(completionCallback != null)
			completionCallback.notifyFailure(req);
	}
	
	public synchronized RequestCompletionCallback setRequestCompletionCallback(RequestCompletionCallback cb) {
		RequestCompletionCallback old = completionCallback;
		completionCallback = cb;
		return old;
	}
}
