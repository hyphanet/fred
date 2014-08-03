/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.IOException;
import java.io.ObjectInput;
import java.util.Map;
import java.util.TreeMap;

import freenet.client.async.ClientContext;
import freenet.node.NodeClientCore;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Persistent root object for FCP.
 * @author toad
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class FCPPersistentRoot {

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
		globalForeverClient = new FCPClient("Global Queue", null, true, null, ClientRequest.PERSIST_FOREVER, this, null);
		clients = new TreeMap<String, FCPClient>();
	}
	
	void setRequestStatusCache(RequestStatusCache cache) {
        globalForeverClient.setRequestStatusCache(cache, null);
	}

    public static FCPPersistentRoot create(ObjectInput ois) throws ClassNotFoundException, IOException {
        FCPPersistentRoot root = (FCPPersistentRoot) ois.readObject();
        return root;
    }

	public synchronized FCPClient registerForeverClient(final String name, NodeClientCore core, FCPConnectionHandler handler, FCPServer server) {
		if(logMINOR) Logger.minor(this, "Registering forever-client for "+name);
		FCPClient client = clients.get(name);
		if(client != null) return client;
		client = new FCPClient(name, handler, false, null, ClientRequest.PERSIST_FOREVER, this, null);
		clients.put(name, client);
		return client;
	}

	public void maybeUnregisterClient(FCPClient client) {
		if((!client.isGlobalQueue) && !client.hasPersistentRequests(null)) {
		    synchronized(this) {
		        clients.remove(client.name);
		    }
		}
	}
	
	public void onResume(ClientContext context) {
	    globalForeverClient.onResume(context);
	    for(FCPClient c : clients.values())
	        c.onResume(context);
	}

}
