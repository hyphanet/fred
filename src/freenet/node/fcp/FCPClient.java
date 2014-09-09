package freenet.node.fcp;

import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.clients.fcp.PersistentRequestClient;
import freenet.clients.fcp.PersistentRequestRoot;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
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
	
    /** The persistent root object, null if persistence is PERSIST_REBOOT */
    final FCPPersistentRoot root;
	/** The client's Name sent in the ClientHello message */
	final String name;
	/** Currently running persistent requests */
	private final List<ClientRequest> runningPersistentRequests;
	/** Completed unacknowledged persistent requests */
	private final List<ClientRequest> completedUnackedRequests;
	/** Are we the global queue? */
	public final boolean isGlobalQueue;
	/** Are we watching the global queue? */
	boolean watchGlobal;
	int watchGlobalVerbosityMask;
    private RequestClient lowLevelClient;
    private RequestClient lowLevelClientRT;
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

	/** Migrate the FCPClient */
    public boolean migrate(PersistentRequestRoot newRoot, ObjectContainer container, NodeClientCore core,
            ClientContext context) {
        int migrated = 0;
        int failed = 0;
        try {
            PersistentRequestClient newClient;
            if(isGlobalQueue) {
                newClient = newRoot.getGlobalForeverClient();
                Logger.error(this, "Migrating global queue");
            } else {
                newClient = newRoot.registerForeverClient(name, null);
                Logger.error(this, "Migrating client \""+name+"\"");
            }
            container.activate(runningPersistentRequests, 2);
            for(ClientRequest req : runningPersistentRequests) {
                if(req == null) continue;
                try {
                    freenet.clients.fcp.ClientRequest request = req.migrate(newClient, container, core);
                    // FIXME it doesn't count as failure if it's already been migrated
                    if(request == null) {
                        failed++;
                        continue;
                    }
                    newClient.register(request);
                    request.start(context);
                    // FIXME catch standard exceptions.
                    migrated++;
                } catch (Throwable t) {
                    Logger.error(this, "Unable to migrate request: "+t, t);
                    failed++;
                }
            }
            container.activate(completedUnackedRequests, 2);
            for(ClientRequest req : completedUnackedRequests) {
                if(req == null) continue;
                try {
                    freenet.clients.fcp.ClientRequest request = req.migrate(newClient, container, core);
                    // FIXME it doesn't count as failure if it's already been migrated
                    if(request == null) {
                        failed++;
                        continue;
                    }
                    newClient.register(request);
                    request.start(context);
                    // FIXME catch standard exceptions.
                    migrated++;
                } catch (Throwable t) {
                    Logger.error(this, "Unable to migrate request: "+t, t);
                    failed++;
                }
            }
            if(failed == 0) {
                if(migrated > 0) {
                    System.out.println("Migrated "+migrated+" requests successfully");
                    Logger.normal(this, "Migrated "+migrated+" requests successfully");
                }
                return true;
            } else {
                if(migrated > 0) {
                    Logger.error(this, "Migrated "+migrated+" requests");
                    System.out.println("Migrated "+migrated+" requests");
                }
                if(failed > 0) {
                    Logger.error(this, "Failed to migrate "+failed+" requests");
                    System.out.println("Failed to migrate "+failed+" requests");
                }
                return false;
            }
        } catch (Throwable t) {
            Logger.error(this, "Unable to migrate client: "+t, t);
            return false;
        }
    }

	@Override
	public String toString() {
		return super.toString()+ ':' +name;
	}

}
