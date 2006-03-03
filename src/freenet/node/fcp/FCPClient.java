package freenet.node.fcp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Vector;

import freenet.client.FetcherContext;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InserterContext;
import freenet.node.Node;
import freenet.support.LRUQueue;

/**
 * An FCP client.
 * Identified by its Name which is sent on connection.
 */
public class FCPClient {

	/** Maximum number of unacknowledged completed requests */
	private static final int MAX_UNACKED_REQUESTS = 256;
	
	public FCPClient(String name2, FCPServer server, FCPConnectionHandler handler) {
		this.name = name2;
		this.currentConnection = handler;
		this.runningPersistentRequests = new HashSet();
		this.completedUnackedRequests = new LRUQueue();
		this.clientRequestsByIdentifier = new HashMap();
		this.server = server;
		this.node = server.node;
		this.client = node.makeClient((short)0);
		defaultFetchContext = client.getFetcherContext();
		defaultInsertContext = client.getInserterContext();
	}
	
	/** The client's Name sent in the ClientHello message */
	final String name;
	/** The FCPServer */
	final FCPServer server;
	/** The current connection handler, if any. */
	private FCPConnectionHandler currentConnection;
	/** Currently running persistent requests */
	private final HashSet runningPersistentRequests;
	/** Completed unacknowledged persistent requests */
	private final LRUQueue completedUnackedRequests;
	/** ClientRequest's by identifier */
	private final HashMap clientRequestsByIdentifier;
	/** Client (one FCPClient = one HighLevelSimpleClient = one round-robin slot) */
	private final HighLevelSimpleClient client;
	public final FetcherContext defaultFetchContext;
	public final InserterContext defaultInsertContext;
	public final Node node;
	
	public synchronized FCPConnectionHandler getConnection() {
		return currentConnection;
	}
	
	public synchronized void setConnection(FCPConnectionHandler handler) {
		this.currentConnection = handler;
	}

	public synchronized void onLostConnection(FCPConnectionHandler handler) {
		if(currentConnection == handler)
			currentConnection = null;
	}

	/**
	 * Called when a client request has finished, but is persistent. It has not been
	 * acked yet, so it should be moved to the unacked-completed-requests set.
	 */
	public void finishedClientRequest(ClientRequest get) {
		ClientRequest dropped = null;
		synchronized(this) {
			runningPersistentRequests.remove(get);
			completedUnackedRequests.push(get);
			
			if(completedUnackedRequests.size() > MAX_UNACKED_REQUESTS) {
				dropped = (ClientRequest) completedUnackedRequests.pop();
			}
		}
		if(dropped != null)
			dropped.dropped();
		if(get.isPersistentForever())
			server.forceStorePersistentRequests();
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
			((ClientRequest)reqs[i]).sendPendingMessages(outputHandler, true);
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
			((ClientRequest)reqs[i]).sendPendingMessages(outputHandler, true);
	}
	
	public void register(ClientRequest cg) {
		synchronized(this) {
			if(cg.hasFinished())
				completedUnackedRequests.push(cg);
			else
				runningPersistentRequests.add(cg);
			clientRequestsByIdentifier.put(cg.getIdentifier(), cg);
		}
	}

	public void removeByIdentifier(String identifier) throws MessageInvalidException {
		ClientRequest req;
		synchronized(this) {
			req = (ClientRequest) clientRequestsByIdentifier.get(identifier);
			if(req == null)
				throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, null, identifier);
			else if(!(runningPersistentRequests.remove(req) || completedUnackedRequests.remove(req)))
				throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, null, identifier);
		}
		server.forceStorePersistentRequests();
	}

	public boolean hasPersistentRequests() {
		return !(runningPersistentRequests.isEmpty() && completedUnackedRequests.isEmpty());
	}

	public void addPersistentRequests(Vector v) {
		synchronized(this) {
			Iterator i = runningPersistentRequests.iterator();
			while(i.hasNext()) {
				ClientRequest req = (ClientRequest) i.next();
				if(req.isPersistentForever())
					v.add(req);
			}
			Object[] unacked = completedUnackedRequests.toArray();
			for(int j=0;j<unacked.length;j++)
				v.add(unacked[j]);
		}
	}
	
}
