/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.pluginmanager.FredPluginFCPMessageHandler.ServerSideFCPMessageHandler;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * <p>Keeps a list of all {@link FCPPluginConnection}s which are connected to server plugins running
 * in the node. Allows the server plugins to query a client connection by its {@link UUID}.</p>
 * 
 * <p>To understand the purpose of this, please consider the following:<br/>
 * The normal flow of plugin FCP is that clients send messages to a server plugin, and the server
 * plugin immediately returns a reply from its message handling function
 * {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
 * FCPPluginMessage)}.<br/>
 * This might not be sufficient for certain usecases: The reply to a message might take quite some
 * time to compute, possibly hours. Then a reference to the original client connection needs to be
 * stored in the plugin's database, not memory.<br/>
 * Thus, this class exists to serve the purpose of allowing plugin servers to query client
 * connections by their ID (see {@link FCPPluginConnection#getID()}).</p>
 * 
 * <p>Client connections are considered as closed once the client discards all strong references
 * to the {@link FCPPluginConnection}. Or in other words: A {@link FCPPluginConnection} is closed
 * once it becomes weakly reachable.<br>
 * Thus, this class is implemented by keeping {@link WeakReference}s to plugin client connections,
 * so they only stay in the memory of the tracker as long as they are still connected.</p>
 * 
 * <p>After constructing an object of this class, you must call {@link #start()} to start its
 * garbage collection thread.<br/>
 * For shutdown, no action is required: The thread will be a daemon thread and thus the JVM will
 * deal with shutdown.</p>
 * 
 * @author xor (xor@freenetproject.org)
 */
final class FCPPluginClientTracker extends NativeThread {
    
    /**
     * Backend table of {@link WeakReference}s to known client connections. Monitored by a
     * {@link ReferenceQueue} to automatically remove entries for connections which have been GCed.
     * 
     * Not a {@link ConcurrentHashMap} because the creation of connections is exposed to the FCP
     * network interface and thus DoS would be possible: Java HashMaps never shrink.
     */
    private final TreeMap<UUID, ConnectionWeakReference> connectionsByID
        = new TreeMap<UUID, ConnectionWeakReference>();

    /**
     * Lock to guard {@link #connectionsByID} against concurrent modification.<br>
     * A {@link ReadWriteLock} because the suspected usage pattern is mostly reads, very few writes
     * - {@link ReadWriteLock} can do that faster than a regular Lock.<br>
     * (A {@link ReentrantReadWriteLock} because thats the only implementation of
     * {@link ReadWriteLock}.)
     */
    private final ReadWriteLock connectionsByIDLock = new ReentrantReadWriteLock();

    /**
     * Queue which monitors nulled weak references in {@link #connectionsByID}.<br>
     * Monitored in {@link #realRun()}.
     */
    private final ReferenceQueue<FCPPluginConnection> closedConnectionsQueue
        = new ReferenceQueue<FCPPluginConnection>();


    /**
     * We extend class {@link WeakReference} so we can store the ID of the connection:<br/>
     * When using a {@link ReferenceQueue} to get notified about nulled {@link WeakReference}
     * values in {@link FCPPluginClientTracker#connectionsByID}, we need to remove those values
     * from the {@link TreeMap}. For fast removal, we need their key in the map, which is the
     * connection ID, so we should store it in the {@link WeakReference}.
     */
    private static final class ConnectionWeakReference
            extends WeakReference<FCPPluginConnection> {

        public final UUID connectionID;

        public ConnectionWeakReference(FCPPluginConnection referent,
                ReferenceQueue<FCPPluginConnection> referenceQueue) {
            
            super(referent, referenceQueue);
            connectionID = referent.getID();
        }
    }

    /**
     * Stores the {@link FCPPluginConnection} so in the future it can be obtained by its ID with
     * {@link FCPPluginClientTracker#getConnection(UUID)}.
     * 
     * <b>Must</b> be called for any newly created {@link FCPPluginConnection} before passing it to
     * {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
     * FCPPluginMessage)}.
     * 
     * Unregistration is not supported and not necessary.
     */
   void registerConnection(FCPPluginConnection connection) {
        connectionsByIDLock.writeLock().lock();
        try {
            // No duplicate checks needed: FCPPluginConnection.getID() is a random UUID.
            connectionsByID.put(connection.getID(),
                new ConnectionWeakReference(connection, closedConnectionsQueue));
        } finally {
            connectionsByIDLock.writeLock().unlock();
        }
    }

    /**
     * For being used by implementors of {@link ServerSideFCPMessageHandler}.<br/>
     * NOT for being used by clients: If you are a client using a {@link FCPPluginConnection} to
     * connect to a server plugin, you have to keep a reference to the {@link FCPPluginConnection}
     * in memory.
     * <br/>
     * This is necessary because this class only keeps {@link WeakReference}s to the
     * {@link FCPPluginConnection} objects. Once they are not referenced by a strong reference,
     * anymore they will be garbage collected and thus considered as disconnected.<br/>
     * The job of keeping the strong references is at the client.
     * 
     * @param connectionID
     *     The value of {@link FCPPluginConnection#getID()} of a client connection which has already
     *     sent a message to your plugin via
     *     {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
     *     FCPPluginMessage)}.
     * @return
     *     The client connection with the given ID, for as long as the client is still connected.
     * @throws IOException
     *     If there has been no connection with the given ID or if the client has disconnected.
     */
    public FCPPluginConnection getConnection(UUID connectionID) throws IOException {
        ConnectionWeakReference ref = null;
        
        connectionsByIDLock.readLock().lock();
        try {
            ref = connectionsByID.get(connectionID);
        } finally {
            connectionsByIDLock.readLock().unlock();
        }
        
        FCPPluginConnection connection = ref != null ? ref.get() : null;
        
        if(connection == null) {
            throw new IOException("FCPPluginConnection not found, maybe client has disconnected."
                + " Connection ID: " + connectionID);
        }
        
        return connection;
    }


    /**
     * You must call {@link #start()} afterwards!
     */
    public FCPPluginClientTracker() {
        super("FCPPluginClientTracker Garbage-collector",
            NativeThread.PriorityLevel.MIN_PRIORITY.value, true);
        setDaemon(true);
    }

    /**
     * Garbage-collection thread: Polls {@link #closedConnectionsQueue} for connections whose
     * {@link WeakReference} has been nulled and removes them from the {@link #connectionsByID}
     * {@link TreeMap}.
     */
    @Override
    public void realRun() {
        while(true) {
            try {
                ConnectionWeakReference closedConnection
                    = (ConnectionWeakReference)closedConnectionsQueue.remove();

                connectionsByIDLock.writeLock().lock();
                try {
                    ConnectionWeakReference removedFromTree
                        = connectionsByID.remove(closedConnection.connectionID);

                    assert(closedConnection == removedFromTree);
                    if(logMINOR) {
                        Logger.minor(this, "Garbage-collecting closed connection: " +
                            "remaining connections = " + connectionsByID.size() +
                            "; connection ID = " + closedConnection.connectionID);
                    }
                } finally {
                    connectionsByIDLock.writeLock().unlock();
                }
            } catch(InterruptedException e) {
                // We did setDaemon(true), which causes the JVM to exit even if the thread is still
                // running: Daemon threads are force terminated during shutdown.
                // Thus, this thread does not need an exit mechanism, it can be an infinite loop. So
                // nothing should try to terminate it by InterruptedException. If it does happen
                // nevertheless, we honor it by exiting the thread, because interrupt requests
                // should never be ignored, but log it as an error.
                Logger.error(this,
                    "Thread interruption requested even though this is a daemon thread!", e);
                throw new RuntimeException(e);
            } catch(Throwable t) {
                Logger.error(this, "Error in thread " + getName(), t);
            }
        }
    }


    /** For {@link Logger#registerClass(Class)} */
    private static transient volatile boolean logMINOR = false;
    
    static {
        Logger.registerClass(FCPPluginClientTracker.class);
    }
}
