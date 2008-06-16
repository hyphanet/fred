package freenet.node.fcp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * An FCP client.
 * Identified by its Name which is sent on connection. 
 * Tracks persistent requests for either PERSISTENCE_REBOOT or PERSISTENCE_FOREVER.
 * 
 * Note that anything that modifies a non-transient field on a PERSISTENCE_FOREVER client should be called in a transaction. 
 * Hence the addition of the ObjectContainer parameter to all such methods.
 */
public class FCPClient {
	
	public FCPClient(String name2, FCPConnectionHandler handler, boolean isGlobalQueue, RequestCompletionCallback cb, short persistenceType, FCPPersistentRoot root) {
		this.name = name2;
		if(name == null) throw new NullPointerException();
		this.currentConnection = handler;
		this.runningPersistentRequests = new HashSet();
		this.completedUnackedRequests = new Vector();
		this.clientRequestsByIdentifier = new HashMap();
		this.isGlobalQueue = isGlobalQueue;
		this.persistenceType = persistenceType;
		assert(persistenceType == ClientRequest.PERSIST_FOREVER || persistenceType == ClientRequest.PERSIST_REBOOT);
		watchGlobalVerbosityMask = Integer.MAX_VALUE;
		toStart = new LinkedList();
		final boolean forever = (persistenceType == ClientRequest.PERSIST_FOREVER);
		lowLevelClient = new RequestClient() {
			public boolean persistent() {
				return forever;
			}
		};
		completionCallback = cb;
		if(persistenceType == ClientRequest.PERSIST_FOREVER) {
			assert(root != null);
			this.root = root;
		} else
			this.root = null;
	}
	
	/** The persistent root object, null if persistenceType is PERSIST_REBOOT */
	final FCPPersistentRoot root;
	/** The client's Name sent in the ClientHello message */
	final String name;
	/** The current connection handler, if any. */
	private transient FCPConnectionHandler currentConnection;
	/** Currently running persistent requests */
	private final HashSet runningPersistentRequests;
	/** Completed unacknowledged persistent requests */
	private final Vector completedUnackedRequests;
	/** ClientRequest's by identifier */
	private final HashMap clientRequestsByIdentifier;
	/** Are we the global queue? */
	public final boolean isGlobalQueue;
	/** Are we watching the global queue? */
	boolean watchGlobal;
	int watchGlobalVerbosityMask;
	/** FCPClients watching us. Lazy init, sync on clientsWatchingLock */
	private transient LinkedList clientsWatching;
	private final Object clientsWatchingLock = new Object();
	private final LinkedList toStart;
	final RequestClient lowLevelClient;
	private transient RequestCompletionCallback completionCallback;
	/** Connection mode */
	final short persistenceType;
	
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
	public void finishedClientRequest(ClientRequest get, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		assert(get.persistenceType == persistenceType);
		synchronized(this) {
			if(runningPersistentRequests.remove(get)) {
				completedUnackedRequests.add(get);
			}	
		}
	}

	/**
	 * Queue any and all pending messages from already completed, unacknowledged, persistent
	 * requests, to be immediately sent. This happens automatically on startup and hopefully
	 * will encourage clients to acknowledge persistent requests!
	 */
	public void queuePendingMessagesOnConnectionRestart(FCPConnectionOutputHandler outputHandler, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
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
	public void queuePendingMessagesFromRunningRequests(FCPConnectionOutputHandler outputHandler, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		Object[] reqs;
		synchronized(this) {
			reqs = runningPersistentRequests.toArray();
		}
		for(int i=0;i<reqs.length;i++)
			((ClientRequest)reqs[i]).sendPendingMessages(outputHandler, true, false, false);
	}
	
	public void register(ClientRequest cg, boolean startLater, ObjectContainer container) throws IdentifierCollisionException {
		assert(cg.persistenceType == persistenceType);
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Registering "+cg.getIdentifier()+(startLater ? " to start later" : ""));
		synchronized(this) {
			String ident = cg.getIdentifier();
			ClientRequest old = (ClientRequest) clientRequestsByIdentifier.get(ident);
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

	public void removeByIdentifier(String identifier, boolean kill, FCPServer server, ObjectContainer container) throws MessageInvalidException {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		ClientRequest req;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "removeByIdentifier("+identifier+ ',' +kill+ ')');
		synchronized(this) {
			req = (ClientRequest) clientRequestsByIdentifier.get(identifier);
			if(req == null)
				throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, "Not in hash", identifier, isGlobalQueue);
			else if(!(runningPersistentRequests.remove(req) || completedUnackedRequests.remove(req)))
				throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, "Not found", identifier, isGlobalQueue);
			clientRequestsByIdentifier.remove(identifier);
		}
        req.requestWasRemoved(container);
		if(kill) {
			if(logMINOR) Logger.minor(this, "Killing request "+req);
			req.cancel();
		}
		if(completionCallback != null)
			completionCallback.onRemove(req);
	}

	public boolean hasPersistentRequests(ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		return !(runningPersistentRequests.isEmpty() && completedUnackedRequests.isEmpty());
	}

	public void addPersistentRequests(Vector v, boolean onlyForever, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		synchronized(this) {
			Iterator i = runningPersistentRequests.iterator();
			while(i.hasNext()) {
				ClientRequest req = (ClientRequest) i.next();
				if(req.isPersistentForever() || !onlyForever)
					v.add(req);
			}
			Object[] unacked = completedUnackedRequests.toArray();
			for(int j=0;j<unacked.length;j++)
				v.add(unacked[j]);
		}
	}

	/**
	 * Enable or disable watch-the-global-queue.
	 * @param enabled Whether we want watch-global-queue to be enabled.
	 * @param verbosityMask If so, what verbosity mask to use (to filter messages
	 * generated by the global queue).
	 */
	public void setWatchGlobal(boolean enabled, int verbosityMask, FCPServer server, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		if(isGlobalQueue) {
			Logger.error(this, "Set watch global on global queue!");
			return;
		}
		if(watchGlobal && !enabled) {
			server.globalRebootClient.unwatch(this);
			server.globalForeverClient.unwatch(this);
			watchGlobal = false;
		} else if(enabled && !watchGlobal) {
			server.globalRebootClient.watch(this);
			server.globalForeverClient.watch(this);
			FCPConnectionHandler connHandler = getConnection();
			if(connHandler != null) {
				server.globalRebootClient.queuePendingMessagesOnConnectionRestart(connHandler.outputHandler, container);
				server.globalForeverClient.queuePendingMessagesOnConnectionRestart(connHandler.outputHandler, container);
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
			synchronized(clientsWatchingLock) {
				if(clientsWatching != null)
				clients = (FCPClient[]) clientsWatching.toArray(new FCPClient[clientsWatching.size()]);
				else
					clients = null;
			}
			if(clients != null)
			for(int i=0;i<clients.length;i++)
				clients[i].queueClientRequestMessage(msg, verbosityLevel);
		}
	}
	
	private void unwatch(FCPClient client) {
		if(!isGlobalQueue) return;
		synchronized(clientsWatchingLock) {
			if(clientsWatching != null)
			clientsWatching.remove(client);
		}
	}

	private void watch(FCPClient client) {
		if(!isGlobalQueue) return;
		synchronized(clientsWatchingLock) {
			if(clientsWatching == null)
				clientsWatching = new LinkedList();
			clientsWatching.add(client);
		}
	}

	public synchronized ClientRequest getRequest(String identifier, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		return (ClientRequest) clientRequestsByIdentifier.get(identifier);
	}

	/**
	 * Start all delayed-start requests.
	 */
	public void finishStart(DBJobRunner runner) {
		ClientRequest[] reqs;
		synchronized(this) {
			reqs = (ClientRequest[]) toStart.toArray(new ClientRequest[toStart.size()]);
		}
		for(int i=0;i<reqs.length;i++) {
			final ClientRequest req = reqs[i];
			runner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					req.start(container, context);
				}
				
			}, NativeThread.HIGH_PRIORITY + reqs[i].getPriority(), false);
		}
	}
	
	public String toString() {
		return super.toString()+ ':' +name;
	}

	/**
	 * Callback called when a request succeeds.
	 */
	public void notifySuccess(ClientRequest req) {
		assert(req.persistenceType == persistenceType);
		if(completionCallback != null)
			completionCallback.notifySuccess(req);
	}

	/**
	 * Callback called when a request fails
	 * @param get
	 */
	public void notifyFailure(ClientRequest req) {
		assert(req.persistenceType == persistenceType);
		if(completionCallback != null)
			completionCallback.notifyFailure(req);
	}
	
	public synchronized RequestCompletionCallback setRequestCompletionCallback(RequestCompletionCallback cb) {
		RequestCompletionCallback old = completionCallback;
		completionCallback = cb;
		return old;
	}

	public void removeFromDatabase(ObjectContainer container) {
		container.delete(runningPersistentRequests);
		container.delete(completedUnackedRequests);
		container.delete(clientRequestsByIdentifier);
		container.delete(toStart);
		container.delete(lowLevelClient);
		container.delete(this);
	}
}
