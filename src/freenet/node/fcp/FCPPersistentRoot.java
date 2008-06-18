/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.node.NodeClientCore;

/**
 * Persistent root object for FCP.
 * @author toad
 */
public class FCPPersistentRoot {

	final long nodeDBHandle;
	final FCPClient globalForeverClient;
	
	public FCPPersistentRoot(long nodeDBHandle) {
		this.nodeDBHandle = nodeDBHandle;
		globalForeverClient = new FCPClient("Global Queue", null, true, null, ClientRequest.PERSIST_REBOOT, this);
	}

	public static FCPPersistentRoot create(final long nodeDBHandle, ObjectContainer container) {
		ObjectSet set = container.query(new Predicate() {
			public boolean match(FCPPersistentRoot root) {
				return root.nodeDBHandle == nodeDBHandle;
			}
		});
		if(set.hasNext()) {
			return (FCPPersistentRoot) set.next();
		}
		FCPPersistentRoot root = new FCPPersistentRoot(nodeDBHandle);
		container.set(root);
		return root;
	}

	public FCPClient registerForeverClient(final String name, NodeClientCore core, FCPConnectionHandler handler, FCPServer server, ObjectContainer container) {
		ObjectSet set = container.query(new Predicate() {
			public boolean match(FCPClient client) {
				if(client.root != FCPPersistentRoot.this) return false;
				return client.name.equals(name);
			}
		});
		if(set.hasNext()) {
			return (FCPClient) set.next();
		}
		FCPClient client = new FCPClient(name, handler, false, null, ClientRequest.PERSIST_FOREVER, this);
		container.set(client);
		return client;
	}

	public void maybeUnregisterClient(FCPClient client, ObjectContainer container) {
		if(!client.hasPersistentRequests(container)) {
			client.removeFromDatabase(container);
		}
	}

}
