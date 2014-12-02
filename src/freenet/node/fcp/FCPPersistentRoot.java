/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.util.ArrayList;
import java.util.List;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Constraint;
import com.db4o.query.Predicate;
import com.db4o.query.Query;

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

	final long nodeDBHandle;
	final FCPClient globalForeverClient;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	protected FCPPersistentRoot() {
	    throw new UnsupportedOperationException();
	}

	public static FCPPersistentRoot load(final long nodeDBHandle, ObjectContainer container) {
		ObjectSet<FCPPersistentRoot> set = container.query(new Predicate<FCPPersistentRoot>() {
			final private static long serialVersionUID = -8615907687034212486L;
			@Override
			public boolean match(FCPPersistentRoot root) {
				return root.nodeDBHandle == nodeDBHandle;
			}
		});
		System.err.println("Count of roots: "+set.size());
		if(set.hasNext()) {
			System.err.println("Loaded FCP persistent root.");
			FCPPersistentRoot root = set.next();
			container.activate(root, 2);
			if(root.globalForeverClient == null) {
				System.err.println("AAAAAAAAAAAAARGH!!!!!!");
				System.err.println("FCPPersistentRoot exists but no globalForeverClient!");
				container.delete(root);
			} else {
				return root;
			}
		}
		System.err.println("No FCPPersistentRoot found, not migrating old database");
		return null;
	}
	
	public List<FCPClient> findNonGlobalClients(NodeClientCore core, ObjectContainer container) {
	    ArrayList<FCPClient> results = new ArrayList<FCPClient>();
		Query query = container.query();
		query.constrain(FCPClient.class);
		ObjectSet<FCPClient> set = query.execute();
		while(true) {
		    try {
		        if(set.hasNext()) {
		            FCPClient client = set.next();
		            try {
		                container.activate(client, 1);
		                if(client.root != this) {
		                    Logger.error(this, "Ignoring client with wrong FCPPersistentRoot");
		                    continue;
		                }
		                if(client.isGlobalQueue) {
		                    Logger.error(this, "Ignoring global queue");
		                    continue;
		                }
		                Logger.error(this, "Will migrate client "+client.name);
		                results.add(client);
		            } catch (Throwable t) {
		                Logger.error(this, "Failed to load client: "+t, t);
		            }
		        } else return results;
		    } catch (Throwable t) {
		        Logger.error(this, "Failed to load clients: "+t, t);
		        return results;
		    }
		}
	}

    public FCPClient getGlobalClient() {
        return globalForeverClient;
    }
}
