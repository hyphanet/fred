/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import freenet.client.async.ClientRequester;
import freenet.node.NodeClientCore;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Tracks persistent requests by FCPClient. Not persistent itself. Populated on startup.
 * @author toad
 */
public class FCPPersistentRoot {

    private static final long serialVersionUID = 1L;
    
    final FCPClient globalForeverClient;
	private final Map<String, FCPClient> clients;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public FCPPersistentRoot() {
		globalForeverClient = new FCPClient("Global Queue", null, true, null, ClientRequest.PERSIST_FOREVER, this);
		clients = new TreeMap<String, FCPClient>();
	}
	
	void setRequestStatusCache(RequestStatusCache cache) {
        globalForeverClient.setRequestStatusCache(cache);
	}

	public FCPClient registerForeverClient(final String name, FCPConnectionHandler handler) {
		if(logMINOR) Logger.minor(this, "Registering forever-client for "+name);
		FCPClient client;
		synchronized(this) {
		    client = clients.get(name);
		    if(client == null)
		        client = new FCPClient(name, handler, false, null, ClientRequest.PERSIST_FOREVER, this);
		    clients.put(name, client);
		}
		client.setConnection(handler);
		return client;
	}

	/** Get the FCPClient if it exists. 
	 * @param handler */
	public FCPClient getForeverClient(final String name, FCPConnectionHandler handler) {
	    FCPClient client;
	    synchronized(this) {
	        client = clients.get(name);
	        if(client == null) return null;
	    }
	    client.setConnection(handler);
        return client;
	}

	public void maybeUnregisterClient(FCPClient client) {
		if((!client.isGlobalQueue) && !client.hasPersistentRequests()) {
		    synchronized(this) {
		        clients.remove(client.name);
		    }
		}
	}

    public ClientRequester[] getPersistentRequesters() {
        List<ClientRequester> requesters = new ArrayList<ClientRequester>();
        globalForeverClient.addPersistentRequesters(requesters);
        for(FCPClient client : clients.values())
            client.addPersistentRequesters(requesters);
        return requesters.toArray(new ClientRequester[requesters.size()]);
    }

    FCPClient resume(ClientRequest clientRequest, boolean global, String clientName) {
        if(global) {
            globalForeverClient.resume(clientRequest);
            return globalForeverClient;
        } else {
            FCPClient client = registerForeverClient(clientName, null);
            client.resume(clientRequest);
            return client;
        }
    }
	
}
