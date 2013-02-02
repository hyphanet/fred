package freenet.node.fcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.client.async.ClientContext;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.fcp.ListPersistentRequestsMessage.PersistentListJob;
import freenet.node.fcp.ListPersistentRequestsMessage.TransientListJob;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.NullObject;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/**
 * An FCP client.
 * Identified by its Name which is sent on connection. 
 * Tracks persistent requests for either PERSISTENCE_REBOOT or PERSISTENCE_FOREVER.
 * 
 * Note that anything that modifies a non-transient field on a PERSISTENCE_FOREVER client should be called in a transaction. 
 * Hence the addition of the ObjectContainer parameter to all such methods.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class FCPClient {
	
	public FCPClient(String name2, FCPConnectionHandler handler, boolean isGlobalQueue, RequestCompletionCallback cb, short persistenceType, FCPPersistentRoot root, ObjectContainer container) {
		this.name = name2;
		if(name == null) throw new NullPointerException();
		this.currentConnection = handler;
		final boolean forever = (persistenceType == ClientRequest.PERSIST_FOREVER);
		runningPersistentRequests = new ArrayList<ClientRequest>();
		completedUnackedRequests = new ArrayList<ClientRequest>();
		clientRequestsByIdentifier = new HashMap<String, ClientRequest>();
		this.isGlobalQueue = isGlobalQueue;
		this.persistenceType = persistenceType;
		assert(persistenceType == ClientRequest.PERSIST_FOREVER || persistenceType == ClientRequest.PERSIST_REBOOT);
		watchGlobalVerbosityMask = Integer.MAX_VALUE;
		lowLevelClient = new FCPClientRequestClient(this, forever, false);
		lowLevelClientRT = new FCPClientRequestClient(this, forever, true);
		completionCallbacks = new ArrayList<RequestCompletionCallback>();
		if(cb != null) completionCallbacks.add(cb);
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
	private RequestClient lowLevelClient;
	private RequestClient lowLevelClientRT;
	private transient List<RequestCompletionCallback> completionCallbacks;
	/** The cache where ClientRequests report their progress */
	private transient RequestStatusCache statusCache;
	/** Connection mode */
	final short persistenceType;
	        
        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

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
		if(logMINOR)
			Logger.minor(this, "Finished client request", new Exception("debug"));
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		assert(get.persistenceType == persistenceType);
		if(container != null) {
			container.activate(runningPersistentRequests, 2);
			container.activate(completedUnackedRequests, 2);
		}
		synchronized(this) {
			if(runningPersistentRequests.remove(get)) {
				completedUnackedRequests.add(get);
				if(container != null) {
					container.store(get);
					// http://tracker.db4o.com/browse/COR-1436
					// If we don't specify depth, we end up updating everything, resulting in Bad Things (especially on ClientPutDir.manifestElements!)
					container.ext().store(runningPersistentRequests, 2);
					container.ext().store(completedUnackedRequests, 2);
				}
			}	
		}
		if(statusCache != null) {
			if(get instanceof ClientGet) {
				ClientGet download = (ClientGet)get;
				GetFailedMessage msg = download.getFailureMessage(container);
				int failureCode = -1;
				String shortFailMessage = null;
				String longFailMessage = null;
				if(msg != null) {
					failureCode = msg.code;
					shortFailMessage = msg.getShortFailedMessage();
					longFailMessage = msg.getLongFailedMessage();
				}
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					container.deactivate(msg, 1);
				Bucket shadow = ((ClientGet) get).getFinalBucket(container);
				if(shadow != null) shadow = shadow.createShadow();
				statusCache.finishedDownload(get.identifier, get.hasSucceeded(), ((ClientGet) get).getDataSize(container), ((ClientGet) get).getMIMEType(container), failureCode, longFailMessage, shortFailMessage, shadow, download.filterData(container));
			} else if(get instanceof ClientPutBase) {
				ClientPutBase upload = (ClientPutBase)get;
				PutFailedMessage msg = upload.getFailureMessage(container);
				int failureCode = -1;
				String shortFailMessage = null;
				String longFailMessage = null;
				if(msg != null) {
					failureCode = msg.code;
					shortFailMessage = msg.getShortFailedMessage();
					longFailMessage = msg.getLongFailedMessage();
				}
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					container.deactivate(msg, 1);
				statusCache.finishedUpload(upload.getIdentifier(), upload.hasSucceeded(), upload.getGeneratedURI(container), failureCode, shortFailMessage, longFailMessage);
			} else assert(false);
		}
	}

	public void queuePendingMessagesOnConnectionRestartAsync(FCPConnectionOutputHandler outputHandler, ObjectContainer container, ClientContext context) {
		if(persistenceType == ClientRequest.PERSIST_FOREVER) {
			PersistentListJob job = new PersistentListJob(this, outputHandler, context) {

				@Override
				void complete(ObjectContainer container, ClientContext context) {
					// Do nothing.
				}
				
			};
			job.run(container, context);
		} else {
			TransientListJob job = new TransientListJob(this, outputHandler, context) {

				@Override
				void complete(ObjectContainer container, ClientContext context) {
					// Do nothing.
				}
				
			};
			job.run(container, context);

		}
	}
	
	
	
	/**
	 * Queue any and all pending messages from already completed, unacknowledged, persistent
	 * requests, to be immediately sent. This happens automatically on startup and hopefully
	 * will encourage clients to acknowledge persistent requests!
	 */
	public int queuePendingMessagesOnConnectionRestart(FCPConnectionOutputHandler outputHandler, ObjectContainer container, int offset, int max) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		Object[] reqs;
		if(container != null) {
			container.activate(completedUnackedRequests, 2);
		}
		synchronized(this) {
			reqs = completedUnackedRequests.toArray();
		}
		int i = 0;
		for(i=offset;i<Math.min(reqs.length,offset+max);i++) {
			ClientRequest req = (ClientRequest) reqs[i];
			if(persistenceType == ClientRequest.PERSIST_FOREVER)
				container.activate(req, 1);
			((ClientRequest)reqs[i]).sendPendingMessages(outputHandler, true, false, false, container);
		}
		return i;
	}
	
	/**
	 * Queue any and all pending messages from running requests. Happens on demand.
	 */
	public int queuePendingMessagesFromRunningRequests(FCPConnectionOutputHandler outputHandler, ObjectContainer container, int offset, int max) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		Object[] reqs;
		if(container != null) {
			container.activate(runningPersistentRequests, 2);
		}
		synchronized(this) {
			reqs = runningPersistentRequests.toArray();
		}
		int i = 0;
		for(i=offset;i<Math.min(reqs.length,offset+max);i++) {
			ClientRequest req = (ClientRequest) reqs[i];
			if(persistenceType == ClientRequest.PERSIST_FOREVER)
				container.activate(req, 1);
			req.sendPendingMessages(outputHandler, true, false, false, container);
		}
		return i;
	}
	
	public void register(ClientRequest cg, ObjectContainer container) throws IdentifierCollisionException {
		assert(cg.persistenceType == persistenceType);
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		if(logMINOR)
			Logger.minor(this, "Registering "+cg.getIdentifier());
		if(container != null) {
			container.activate(completedUnackedRequests, 2);
			container.activate(runningPersistentRequests, 2);
			container.activate(clientRequestsByIdentifier, 2);
		}
		synchronized(this) {
			String ident = cg.getIdentifier();
			ClientRequest old = clientRequestsByIdentifier.get(ident);
			if((old != null) && (old != cg))
				throw new IdentifierCollisionException();
			if(cg.hasFinished()) {
				completedUnackedRequests.add(cg);
				if(container != null) {
					container.store(cg);
					container.ext().store(completedUnackedRequests, 2);
				}
			} else {
				runningPersistentRequests.add(cg);
				if(container != null) {
					cg.storeTo(container);
					container.ext().store(runningPersistentRequests, 2);
				}
			}
			clientRequestsByIdentifier.put(ident, cg);
			if(container != null) container.ext().store(clientRequestsByIdentifier, 2);
		}
		if(statusCache != null) {
			if(cg instanceof ClientGet) {
				statusCache.addDownload((DownloadRequestStatus)(cg.getStatus(container)));
			} else if(cg instanceof ClientPutBase) {
				statusCache.addUpload((UploadRequestStatus)(cg.getStatus(container)));
			}
		}
	}

	public boolean removeByIdentifier(String identifier, boolean kill, FCPServer server, ObjectContainer container, ClientContext context) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		ClientRequest req;
		if(logMINOR) Logger.minor(this, "removeByIdentifier("+identifier+ ',' +kill+ ')');
		if(container != null) {
			container.activate(completedUnackedRequests, 2);
			container.activate(runningPersistentRequests, 2);
			container.activate(clientRequestsByIdentifier, 2);
		}
		if(statusCache != null)
			statusCache.removeByIdentifier(identifier);
		synchronized(this) {
			req = clientRequestsByIdentifier.get(identifier);
//			if(container != null && req != null)
//				container.activate(req, 1);
			boolean removedFromRunning = false;
			if(req == null) {
				for(ClientRequest r : completedUnackedRequests) {
					if(persistenceType == ClientRequest.PERSIST_FOREVER)
						container.activate(r, 1);
					if(r.getIdentifier().equals(identifier)) {
						req = r;
						completedUnackedRequests.remove(r);
						Logger.error(this, "Found completed unacked request "+r+" for identifier "+r.getIdentifier()+" but not in clientRequestsByIdentifier!!");
						break;
					}
					if(persistenceType == ClientRequest.PERSIST_FOREVER)
						container.deactivate(r, 1);
				}
				if(req == null) {
					for(ClientRequest r : runningPersistentRequests) {
						if(persistenceType == ClientRequest.PERSIST_FOREVER)
							container.activate(r, 1);
						if(r.getIdentifier().equals(identifier)) {
							req = r;
							runningPersistentRequests.remove(r);
							removedFromRunning = true;
							Logger.error(this, "Found running request "+r+" for identifier "+r.getIdentifier()+" but not in clientRequestsByIdentifier!!");
							break;
						}
						if(persistenceType == ClientRequest.PERSIST_FOREVER)
							container.deactivate(r, 1);
					}
				}
				if(req == null) return false;
			} else if(!((removedFromRunning = runningPersistentRequests.remove(req)) || completedUnackedRequests.remove(req))) {
				Logger.error(this, "Removing "+identifier+": in clientRequestsByIdentifier but not in running/completed maps!");
				
				return false;
			}
			clientRequestsByIdentifier.remove(identifier);
			if(container != null) {
				if(removedFromRunning) container.ext().store(runningPersistentRequests, 2);
				else container.ext().store(completedUnackedRequests, 2);
				container.ext().store(clientRequestsByIdentifier, 2);
			}
		}
		if(container != null)
			container.activate(req, 1);
		if(kill) {
			if(logMINOR) Logger.minor(this, "Killing request "+req);
			req.cancel(container, context);
		}
        req.requestWasRemoved(container, context);
        RequestCompletionCallback[] callbacks = null;
        synchronized(this) {
        	if(completionCallbacks != null)
        		callbacks = completionCallbacks.toArray(new RequestCompletionCallback[completionCallbacks.size()]);
        }
		if(callbacks != null) {
			for(RequestCompletionCallback cb : callbacks)
				cb.onRemove(req, container);
		}
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
		if(container != null) {
			container.activate(completedUnackedRequests, 2);
			container.activate(runningPersistentRequests, 2);
		}
		return !(runningPersistentRequests.isEmpty() && completedUnackedRequests.isEmpty());
	}

	public void addPersistentRequests(List<ClientRequest> v, boolean onlyForever, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		if(container != null) {
			container.activate(completedUnackedRequests, 2);
			container.activate(runningPersistentRequests, 2);
			container.activate(clientRequestsByIdentifier, 2);
		}
		synchronized(this) {
			for(ClientRequest req: runningPersistentRequests) {
				if(container != null) container.activate(req, 1);
				if(req == null) {
					Logger.error(this, "Request is null on runningPersistentRequests for "+this+" - database corruption??");
					continue;
				}
				if((req.isPersistentForever()) || !onlyForever)
					v.add(req);
			}
			if(container != null) {
				for(ClientRequest req : completedUnackedRequests) {
					container.activate(req, 1);
				}
			}
			v.addAll(completedUnackedRequests);
		}
	}
	
	/** From database */
	private void addPersistentRequestStatus(List<RequestStatus> status, boolean onlyForever,
			ObjectContainer container) {
		// FIXME OPT merge with addPersistentRequests? Locking looks tricky.
		List<ClientRequest> reqs = new ArrayList<ClientRequest>();
		addPersistentRequests(reqs, onlyForever, container);
		for(ClientRequest req : reqs) {
			try {
				status.add(req.getStatus(container));
			} catch (Throwable t) {
				// Try to load the rest. :<
				Logger.error(this, "BROKEN REQUEST LOADING PERSISTENT REQUEST STATUS: "+t, t);
				// FIXME tell the user in wrapper.log or even in a useralert.
			}
			// FIXME deactivate? Unconditional deactivate depends on callers. Keep-as-is would need merge with addPersistentRequests.
		}
	}
	
	/** From cache */
	public void addPersistentRequestStatus(List<RequestStatus> status) {
		statusCache.addTo(status);
	}

	/**
	 * Enable or disable watch-the-global-queue.
	 * @param enabled Whether we want watch-global-queue to be enabled.
	 * @param verbosityMask If so, what verbosity mask to use (to filter messages
	 * generated by the global queue).
	 */
	public boolean setWatchGlobal(boolean enabled, int verbosityMask, FCPServer server, ObjectContainer container) {
		assert((persistenceType == ClientRequest.PERSIST_FOREVER) == (container != null));
		if(isGlobalQueue) {
			Logger.error(this, "Set watch global on global queue!: "+this, new Exception("debug"));
			return false;
		}
		if(server.globalForeverClient == null) return false;
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
					server.globalRebootClient.queuePendingMessagesOnConnectionRestartAsync(connHandler.outputHandler, container, server.core.clientContext);
				else
					server.globalForeverClient.queuePendingMessagesOnConnectionRestartAsync(connHandler.outputHandler, container, server.core.clientContext);
			}
			watchGlobal = true;
		}
		// Otherwise the status is unchanged.
		this.watchGlobalVerbosityMask = verbosityMask;
		return true;
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
				clients = clientsWatching.toArray(new FCPClient[clientsWatching.size()]);
				else
					clients = null;
			}
			if(clients != null)
			for(FCPClient client: clients) {
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					container.activate(client, 1);
				if(client.persistenceType != persistenceType) continue;
				client.queueClientRequestMessage(msg, verbosityLevel, true, container);
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					container.deactivate(client, 1);
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
		if(container != null) {
			container.activate(clientRequestsByIdentifier, 2);
		}
		ClientRequest req = clientRequestsByIdentifier.get(identifier);
		if(persistenceType == ClientRequest.PERSIST_FOREVER)
			container.activate(req, 1);
		return req;
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
        RequestCompletionCallback[] callbacks = null;
        synchronized(this) {
        	if(completionCallbacks != null)
        		callbacks = completionCallbacks.toArray(new RequestCompletionCallback[completionCallbacks.size()]);
        }
		if(callbacks != null) {
			for(RequestCompletionCallback cb : callbacks)
				cb.notifySuccess(req, container);
		}
	}

	/**
	 * Callback called when a request fails
	 * @param get
	 */
	public void notifyFailure(ClientRequest req, ObjectContainer container) {
		assert(req.persistenceType == persistenceType);
        RequestCompletionCallback[] callbacks = null;
        synchronized(this) {
        	if(completionCallbacks != null)
        		callbacks = completionCallbacks.toArray(new RequestCompletionCallback[completionCallbacks.size()]);
        }
		if(callbacks != null) {
			for(RequestCompletionCallback cb : callbacks)
				cb.notifyFailure(req, container);
		}
	}
	
	public synchronized void addRequestCompletionCallback(RequestCompletionCallback cb) {
		if(completionCallbacks == null) completionCallbacks = new ArrayList<RequestCompletionCallback>(); // it is transient so it might be null
		completionCallbacks.add(cb);
	}
	
	public synchronized void removeRequestCompletionCallback(RequestCompletionCallback cb){
		if(completionCallbacks!=null) completionCallbacks.remove(cb);
	}

	public void removeFromDatabase(ObjectContainer container) {
		container.activate(runningPersistentRequests, 2);
		container.delete(runningPersistentRequests);
		container.activate(completedUnackedRequests, 2);
		container.delete(completedUnackedRequests);
		container.activate(clientRequestsByIdentifier, 2);
		container.delete(clientRequestsByIdentifier);
		container.activate(lowLevelClient, 2);
		lowLevelClient.removeFrom(container);
		container.delete(this);
		container.delete(clientsWatchingLock);
	}

	public void removeAll(ObjectContainer container, ClientContext context) {
		HashSet<ClientRequest> toKill = new HashSet<ClientRequest>();
		if(container != null) {
			container.activate(completedUnackedRequests, 2);
			container.activate(runningPersistentRequests, 2);
			container.activate(clientRequestsByIdentifier, 2);
		}
		if(statusCache != null)
			statusCache.clear();
		synchronized(this) {
			for(ClientRequest req: runningPersistentRequests) {
				toKill.add(req);
			}
			runningPersistentRequests.clear();
			for(ClientRequest req : completedUnackedRequests) {
				if(persistenceType == ClientRequest.PERSIST_FOREVER) container.activate(req, 1);
				toKill.add(req);
			}
			completedUnackedRequests.clear();
			for(ClientRequest req : clientRequestsByIdentifier.values()) {
				if(persistenceType == ClientRequest.PERSIST_FOREVER) container.activate(req, 1);
				toKill.add(req);
			}
			clientRequestsByIdentifier.clear();
			if (persistenceType == ClientRequest.PERSIST_FOREVER)
				container.ext().store(clientRequestsByIdentifier, 2);
		}
	}

	public ClientGet getCompletedRequest(FreenetURI key, ObjectContainer container) {
		// FIXME speed this up with another hashmap or something.
		// FIXME keep a transient hashmap in RAM, use it for fproxy.
		// FIXME consider supporting inserts too.
		if(container != null) {
			container.activate(completedUnackedRequests, 2);
		}
		for(int i=0;i<completedUnackedRequests.size();i++) {
			ClientRequest req = completedUnackedRequests.get(i);
			if(!(req instanceof ClientGet)) continue;
			ClientGet getter = (ClientGet) req;
			if(persistenceType == ClientRequest.PERSIST_FOREVER)
				container.activate(getter, 1);
			if(getter.getURI(container).equals(key)) {
				return getter;
			} else {
				if(persistenceType == ClientRequest.PERSIST_FOREVER)
					container.deactivate(getter, 1);
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public void init(ObjectContainer container) {
		if(!container.ext().isActive(this))
			throw new IllegalStateException("Initialising but not activated");
		container.activate(runningPersistentRequests, 2);
		container.activate(completedUnackedRequests, 2);
		container.activate(clientRequestsByIdentifier, 2);
		container.activate(lowLevelClient, 2);
		assert runningPersistentRequests != null;
		assert completedUnackedRequests != null;
		assert clientRequestsByIdentifier != null;
		if(lowLevelClient == null) {
			System.err.println("No lowLevelClient for "+this+" but other fields exist.");
			System.err.println("This means your database has been corrupted slightly, probably by a bug in Freenet.");
			System.err.println("We are trying to recover ...");
			Query q = container.query();
			q.constrain(FCPClientRequestClient.class);
			q.descend("client").constrain(this);
			ObjectSet<FCPClientRequestClient> results = q.execute();
			for(FCPClientRequestClient c : results) {
				if(c.client != this) continue;
				System.err.println("Found the old request client, eh???");
				lowLevelClient = c;
				break;
			}
			if(lowLevelClient == null)
				lowLevelClient = new FCPClientRequestClient(this, persistenceType == ClientRequest.PERSIST_FOREVER, false);
			container.store(lowLevelClient);
			container.store(this);
		}
		if(lowLevelClientRT == null) {
			// FIXME remove
			lowLevelClientRT = new FCPClientRequestClient(this, persistenceType == ClientRequest.PERSIST_FOREVER, true);
		}
		//assert lowLevelClient != null;
	}

	public boolean objectCanNew(ObjectContainer container) {
		if(persistenceType != ClientRequest.PERSIST_FOREVER) {
			Logger.error(this, "Not storing non-persistent request in database", new Exception("error"));
			return false;
		}
		if(lowLevelClient == null)
			throw new NullPointerException(); // Better it happens here ...
		return true;
	}
	
	public void objectCanUpdate(ObjectContainer container) {
		if(lowLevelClient == null)
			throw new NullPointerException(); // Better it happens here ...
	}

	public RequestStatusCache getRequestStatusCache() {
		return statusCache;
	}
	
	public void setRequestStatusCache(RequestStatusCache cache, ObjectContainer container) {
		statusCache = cache;
		if(persistenceType == ClientRequest.PERSIST_FOREVER) {
			System.out.println("Loading cache of request statuses...");
			ArrayList<RequestStatus> statuses = new ArrayList<RequestStatus>();
			addPersistentRequestStatus(statuses, true, container);
			for(RequestStatus status : statuses) {
				if(status instanceof DownloadRequestStatus)
					cache.addDownload((DownloadRequestStatus)status);
				else
					cache.addUpload((UploadRequestStatus)status);
			}
		}
	}

	public RequestClient lowLevelClient(boolean realTime) {
		if(realTime)
			return lowLevelClientRT;
		else
			return lowLevelClient;
	}

}
