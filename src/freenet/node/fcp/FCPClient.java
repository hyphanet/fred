package freenet.node.fcp;

import java.util.List;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

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
    
    private FCPClient() {
        // Only read in from database. Is not created.
        throw new UnsupportedOperationException();
    }
	
	/** The client's Name sent in the ClientHello message */
	final String name;
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

}
