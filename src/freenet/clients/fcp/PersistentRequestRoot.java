/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Tracks persistent requests by PersistentRequestClient. Not persistent itself. Populated on startup.
 * @author toad
 */
public class PersistentRequestRoot {

    private static final long serialVersionUID = 1L;
    
    final PersistentRequestClient globalForeverClient;
	private final Map<String, PersistentRequestClient> clients;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public PersistentRequestRoot() {
		globalForeverClient = new PersistentRequestClient("Global Queue", null, true, null, Persistence.FOREVER, this);
		clients = new TreeMap<String, PersistentRequestClient>();
	}
	
	public PersistentRequestClient registerForeverClient(final String name, FCPConnectionHandler handler) {
		if(logMINOR) Logger.minor(this, "Registering forever-client for "+name);
		PersistentRequestClient client;
		synchronized(this) {
		    client = clients.get(name);
		    if(client == null)
		        client = new PersistentRequestClient(name, handler, false, null, Persistence.FOREVER, this);
		    clients.put(name, client);
		}
		if(handler != null)
		    client.setConnection(handler);
		return client;
	}

	/** Get the PersistentRequestClient if it exists. 
	 * @param handler */
	public PersistentRequestClient getForeverClient(final String name, FCPConnectionHandler handler) {
	    PersistentRequestClient client;
	    synchronized(this) {
	        client = clients.get(name);
	        if(client == null) return null;
	    }
	    if(handler != null)
	        client.setConnection(handler);
        return client;
	}

	public void maybeUnregisterClient(PersistentRequestClient client) {
		if((!client.isGlobalQueue) && !client.hasPersistentRequests()) {
		    synchronized(this) {
		        clients.remove(client.name);
		    }
		}
	}

    public ClientRequest[] getPersistentRequests() {
        List<ClientRequest> requests = new ArrayList<ClientRequest>();
        globalForeverClient.addPersistentRequests(requests, true);
        for(PersistentRequestClient client : clients.values())
            client.addPersistentRequests(requests, true);
        return requests.toArray(new ClientRequest[requests.size()]);
    }

    PersistentRequestClient resume(ClientRequest clientRequest, boolean global, String clientName) {
        PersistentRequestClient client = makeClient(global, clientName);
        client.resume(clientRequest);
        return client;
    }
    
    PersistentRequestClient makeClient(boolean global, String clientName) {
        if(global) {
            return globalForeverClient;
        } else {
            return registerForeverClient(clientName, null);
        }
    }

    public synchronized boolean hasRequest(RequestIdentifier req) {
        PersistentRequestClient client;
        if(req.globalQueue)
            client = globalForeverClient;
        else
            client = getForeverClient(req.clientName, null);
        if(client == null) return false;
        return client.getRequest(req.identifier) != null;
    }

    public PersistentRequestClient getGlobalForeverClient() {
        return globalForeverClient;
    }
	
}
