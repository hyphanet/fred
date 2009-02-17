package freenet.node.fcp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import com.db4o.ObjectContainer;
import com.db4o.types.Db4oList;
import com.db4o.types.Db4oMap;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.NullObject;
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
	
	public FCPClient(String name2, FCPConnectionHandler handler, boolean isGlobalQueue, RequestCompletionCallback cb, short persistenceType, FCPPersistentRoot root, ObjectContainer container) {
		this.name = name2;
		if(name == null) throw new NullPointerException();
		this.currentConnection = handler;
		final boolean forever = (persistenceType == ClientRequest.PERSIST_FOREVER);
		if(forever) {
			runningPersistentRequests = container.ext().collections().newLinkedList();
			((Db4oList)runningPersistentRequests).activationDepth(1);
			completedUnackedRequests = container.ext().collections().newLinkedList();
			((Db4oList)completedUnackedRequests).activationDepth(1);
			clientRequestsByIdentifier = container.ext().collections().newHashMap(10);
			((Db4oMap)clientRequestsByIdentifier).activationDepth(1);
		} else {
			runningPersistentRequests = new Vector();
			completedUnackedRequests = new Vector();
			clientRequestsByIdentifier = new HashMap();
		}
		this.isGlobalQueue = isGlobalQueue;
		this.persistenceType = persistenceType;
		assert(persistenceType == ClientRequest.PERSIST_FOREVER || persistenceType == ClientRequest.PERSIST_REBOOT);
		watchGlobalVerbosityMask = Integer.MAX_VALUE;
		toStart = new LinkedList<ClientRequest>();
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
	private final List<ClientRequest> runningPersistentRequests;
	/** Completed unacknowledged persistent requests */
	private final List<ClientRequest> completedUnackedRequests;
	/** ClientRequest's by identifier */
	private final Map<String, ClientRequest> clientRequestsByIdentifier;
	/** Are we the global queue? */
	public final boolean isGlobalQueue;
	/** Are we watching the global queue? */
	boolean watchGlobal;
	int watchGlobalVerbosityMask;
	/** FCPClients watching us. Lazy init, sync on clientsWatchingLock */
	private transient LinkedList<FCPClient> clientsWatching;
	private final NullObject clientsWatchingLock = new NullObject();
	private final LinkedList<ClientRequest> toStart;
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
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Finished client request", new Exception("debug"));
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
		for(int i=0;i<reqs.length;i++) {
			ClientRequest req = (ClientRequest) reqs[i];
			if(persistenceType == ClientRequest.PERSIST_FOREVER)
				container.activate(req, 1);
			((ClientRequest)reqs[i]).sendPendingMessages(outputHandler, true, false, false, container);
		}
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
		for(int i=0;i<reqs.length;i++) {
			ClientRequest req = (ClientRequest) reqs[i];
			if(persistenceType == ClientRequest.PERSIST_FOREVER)
				container.activate(req, 1);
			req.sendPendingMessages(outputHandler, true, false, false, container);
		}
	}
	
	public void register(ClientRequest cg, boolean startLater, ObjectContainer container) throws IdentifierCollisionException {
		assert(cg.persistenceType == persistenceType);
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
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

	public boolean removeByIdentifier(String identifier, boolean kill, FCPServer server, ObjectContainer container, ClientContext context) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		ClientRequest req;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "removeByIdentifier("+identifier+ ',' +kill+ ')');
		synchronized(this) {
			req = clientRequestsByIdentifier.get(identifier);
			if(req == null)
				return false;
			else if(!(runningPersistentRequests.remove(req) || completedUnackedRequests.remove(req))) {
				Logger.error(this, "Removing "+identifier+": in clientRequestsByIdentifier but not in running/completed maps!");
				return false;
			}
			clientRequestsByIdentifier.remove(identifier);
		}
		if(kill) {
			if(logMINOR) Logger.minor(this, "Killing request "+req);
			req.cancel(container, context);
		}
        req.requestWasRemoved(container);
		if(completionCallback != null)
			completionCallback.onRemove(req, container);
		return true;
	}

	public boolean hasPersistentRequests(ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		if(runningPersistentRequests == null) {
			if(!container.ext().isActive(this))
				Logger.error(this, "FCPCLIENT NOT ACTIVE!!!");
			throw new NullPointerException();
		}
		if(completedUnackedRequests == null) {
			if(!container.ext().isActive(this))
				Logger.error(this, "FCPCLIENT NOT ACTIVE!!!");
			throw new NullPointerException();
		}
		return !(runningPersistentRequests.isEmpty() && completedUnackedRequests.isEmpty());
	}

	public void addPersistentRequests(List<ClientRequest> v, boolean onlyForever, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
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
	public void setWatchGlobal(boolean enabled, int verbosityMask, FCPServer server, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		if(isGlobalQueue) {
			Logger.error(this, "Set watch global on global queue!: "+this, new Exception("debug"));
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
				if(persistenceType == ClientRequest.PERSIST_REBOOT)
					server.globalRebootClient.queuePendingMessagesOnConnectionRestart(connHandler.outputHandler, container);
				else
					server.globalForeverClient.queuePendingMessagesOnConnectionRestart(connHandler.outputHandler, container);
			}
			watchGlobal = true;
		}
		// Otherwise the status is unchanged.
		this.watchGlobalVerbosityMask = verbosityMask;
	}

	public void queueClientRequestMessage(FCPMessage msg, int verbosityLevel, ObjectContainer container) {
		queueClientRequestMessage(msg, verbosityLevel, false, container);
	}
	
	public void queueClientRequestMessage(FCPMessage msg, int verbosityLevel, boolean useGlobalMask, ObjectContainer container) {
		if(useGlobalMask && (verbosityLevel & watchGlobalVerbosityMask) != verbosityLevel)
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
			for(int i=0;i<clients.length;i++) {
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					container.activate(clients[i], 1);
				if(clients[i].persistenceType != persistenceType) continue;
				clients[i].queueClientRequestMessage(msg, verbosityLevel, true, container);
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					container.deactivate(clients[i], 1);
			}
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
				clientsWatching = new LinkedList<FCPClient>();
			clientsWatching.add(client);
		}
	}

	public synchronized ClientRequest getRequest(String identifier, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		ClientRequest req = (ClientRequest) clientRequestsByIdentifier.get(identifier);
		if(persistenceType == ClientRequest.PERSIST_FOREVER)
			container.activate(req, 1);
		return req;
	}

	/**
	 * Start all delayed-start requests.
	 */
	public void finishStart(DBJobRunner runner) {
		ClientRequest[] reqs;
		synchronized(this) {
			reqs = (ClientRequest[]) toStart.toArray(new ClientRequest[toStart.size()]);
			toStart.clear();
		}
		for(int i=0;i<reqs.length;i++) {
			final ClientRequest req = reqs[i];
			runner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(req, 1);
					req.start(container, context);
					container.deactivate(req, 1);
				}
				
			}, NativeThread.HIGH_PRIORITY + reqs[i].getPriority(), false);
		}
	}
	
	@Override
	public String toString() {
		return super.toString()+ ':' +name;
	}

	/**
	 * Callback called when a request succeeds.
	 */
	public void notifySuccess(ClientRequest req, ObjectContainer container) {
		assert(req.persistenceType == persistenceType);
		if(completionCallback != null)
			completionCallback.notifySuccess(req, container);
	}

	/**
	 * Callback called when a request fails
	 * @param get
	 */
	public void notifyFailure(ClientRequest req, ObjectContainer container) {
		assert(req.persistenceType == persistenceType);
		if(completionCallback != null)
			completionCallback.notifyFailure(req, container);
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

	public void removeAll(ObjectContainer container, ClientContext context) {
		HashSet<ClientRequest> toKill = new HashSet<ClientRequest>();
		synchronized(this) {
			Iterator i = runningPersistentRequests.iterator();
			while(i.hasNext()) {
				ClientRequest req = (ClientRequest) i.next();
				toKill.add(req);
			}
			runningPersistentRequests.clear();
			for(int j=0;j<completedUnackedRequests.size();j++)
				toKill.add(completedUnackedRequests.get(j));
			completedUnackedRequests.clear();
			i = clientRequestsByIdentifier.values().iterator();
			while(i.hasNext()) {
				ClientRequest req = (ClientRequest) i.next();
				toKill.add(req);
			}
			clientRequestsByIdentifier.clear();
			i = toStart.iterator();
			while(i.hasNext()) {
				ClientRequest req = (ClientRequest) i.next();
				toKill.add(req);
			}
			toStart.clear();
		}
		Iterator i = toStart.iterator();
		while(i.hasNext()) {
			ClientRequest req = (ClientRequest) i.next();
			req.cancel(container, context);
			req.requestWasRemoved(container);
		}
	}

	public ClientGet getCompletedRequest(FreenetURI key, ObjectContainer container) {
		// FIXME speed this up with another hashmap or something.
		// FIXME keep a transient hashmap in RAM, use it for fproxy.
		// FIXME consider supporting inserts too.
		for(int i=0;i<completedUnackedRequests.size();i++) {
			ClientRequest req = (ClientRequest) completedUnackedRequests.get(i);
			if(!(req instanceof ClientGet)) continue;
			ClientGet getter = (ClientGet) req;
			if(getter.getURI(container).equals(key)) {
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					container.activate(getter, 1);
				return getter;
			}
		}
		return null;
	}

	public void init(ObjectContainer container) {
		container.activate(runningPersistentRequests, 1);
		container.activate(completedUnackedRequests, 1);
		container.activate(clientRequestsByIdentifier, 1);
		((Db4oList)runningPersistentRequests).activationDepth(1);
		((Db4oList)completedUnackedRequests).activationDepth(1);
		((Db4oMap)clientRequestsByIdentifier).activationDepth(1);
	}

	public boolean objectCanNew(ObjectContainer container) {
		if(persistenceType != ClientRequest.PERSIST_FOREVER) {
			Logger.error(this, "Not storing non-persistent request in database", new Exception("error"));
			return false;
		}
		return true;
	}


}
