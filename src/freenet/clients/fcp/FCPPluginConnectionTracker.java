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

import freenet.clients.fcp.FCPPluginConnection.SendDirection;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ClientSideFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ServerSideFCPMessageHandler;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * Keeps a list of all {@link FCPPluginConnectionImpl}s which are connected to server plugins
 * running in the node. Allows the server plugins to query a client connection by its {@link UUID}.
 * <br><br>
 * 
 * <p>To understand the purpose of this, please consider the following:<br/>
 * The normal flow of plugin FCP is that clients send messages to a server plugin, and the server
 * plugin immediately returns a reply from its message handling function
 * {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
 * FCPPluginMessage)}.<br/>
 * This might not be sufficient for certain usecases: The reply to a message might take quite some
 * time to compute, possibly hours. Then a reference to the original client connection needs to be
 * stored in the plugin's database, not memory. A {@link FCPPluginConnection} cannot be
 * serialized into a database, but an {@link UUID} can.<br>
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
final class FCPPluginConnectionTracker extends NativeThread {
    
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
    private final ReferenceQueue<FCPPluginConnectionImpl> closedConnectionsQueue
        = new ReferenceQueue<FCPPluginConnectionImpl>();


    /**
     * We extend class {@link WeakReference} so we can store the ID of the connection:<br/>
     * When using a {@link ReferenceQueue} to get notified about nulled {@link WeakReference}
     * values in {@link FCPPluginConnectionTracker#connectionsByID}, we need to remove those values
     * from the {@link TreeMap}. For fast removal, we need their key in the map, which is the
     * connection ID, so we should store it in the {@link WeakReference}.
     */
    static final class ConnectionWeakReference
            extends WeakReference<FCPPluginConnectionImpl> {

        public final UUID connectionID;

        public ConnectionWeakReference(FCPPluginConnectionImpl referent,
                ReferenceQueue<FCPPluginConnectionImpl> referenceQueue) {
            
            super(referent, referenceQueue);
            connectionID = referent.getID();
        }
    }

    /**
     * Stores the {@link FCPPluginConnectionImpl} so in the future it can be obtained by its ID with
     * {@link #getConnection(UUID)}.
     * 
     * <b>Must</b> be called for any newly created {@link FCPPluginConnectionImpl} before passing it
     * to {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
     * FCPPluginMessage)}.
     * 
     * Unregistration is not supported and not necessary.
     */
   void registerConnection(FCPPluginConnectionImpl connection) {
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
     * For being indirectly exposed to implementors of server plugins, i.e. implementors of
     * {@link ServerSideFCPMessageHandler}.<br/>
     * NOT for being used by clients: Clients using a {@link FCPPluginConnection} to connect to a
     * server plugin have to keep a reference to the {@link FCPPluginConnection} in memory.
     * See {@link PluginRespirator#connectToOtherPlugin(String, ClientSideFCPMessageHandler)}.
     * <br/>
     * This is necessary because this class only keeps {@link WeakReference}s to the
     * {@link FCPPluginConnection} objects. Once they are not referenced by a strong reference
     * anymore they will be garbage collected and thus considered as disconnected.<br/>
     * The job of keeping the strong references is at the client.<br><br>
     * 
     * ATTENTION:<br>
     * The returned FCPPluginConnectionImpl objects class shall not be handed out directly to server
     * applications. Instead, only hand out a {@link DefaultSendDirectionAdapter} - which can be
     * obtained by {@link FCPPluginConnectionImpl#getDefaultSendDirectionAdapter(SendDirection)}.
     * <br>This has two reasons:<br>
     * - The send functions which do not require a {@link SendDirection} will always throw an
     *   exception without an adapter ({@link #send(FCPPluginMessage)} and
     *   {@link #sendSynchronous(FCPPluginMessage, long)}).<br>
     * - Server plugins must not keep a strong reference to the FCPPluginConnectionImpl
     *   to ensure that the client disconnection mechanism of monitoring garbage collection works.
     *   The adapter prevents servers from keeping a strong reference by internally only keeping a
     *   {@link WeakReference} to the FCPPluginConnectionImpl.<br>
     * 
     * @param connectionID
     *     The value of {@link FCPPluginConnection#getID()} of a client connection which has already
     *     sent a message to the server plugin via
     *     {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
     *     FCPPluginMessage)}.
     * @return
     *     The client connection with the given ID, for as long as the client is still connected.
     * @throws IOException
     *     If there has been no connection with the given ID or if the client has disconnected.
     */
    public FCPPluginConnectionImpl getConnection(UUID connectionID) throws IOException {
        ConnectionWeakReference ref = getConnectionWeakReference(connectionID);

        FCPPluginConnectionImpl connection = ref.get();
        
        if(connection == null) {
            throw new IOException("Client has closed the connection. "
                                + "Connection ID = " + connectionID);
        }
        
        return connection;
    }
    
    /**
     * Same as {@link #getConnection(UUID)} with the only difference of returning a
     * {@link WeakReference} to the connection instead of the connection itself.<br>
     * <b>Please do read its JavaDoc to understand when to use this!</b>
     */
    ConnectionWeakReference getConnectionWeakReference(UUID connectionID)
            throws IOException {
        
        connectionsByIDLock.readLock().lock();
        try {
            ConnectionWeakReference ref = connectionsByID.get(connectionID);
            if(ref != null)
                return ref;
        } finally {
            connectionsByIDLock.readLock().unlock();
        }
        
        throw new IOException("FCPPluginConnection not found, maybe client has disconnected."
                            + " Connection ID: " + connectionID);
    }


    /**
     * You must call {@link #start()} afterwards!
     */
    public FCPPluginConnectionTracker() {
        super("FCPPluginConnectionTracker Garbage-collector",
            NativeThread.PriorityLevel.MIN_PRIORITY.value, true);
        setDaemon(true);
    }

    /**
     * Garbage-collection thread: Polls {@link #closedConnectionsQueue} for connections whose
     * {@link WeakReference} has been nulled and removes them from the {@link #connectionsByID}
     * {@link TreeMap}.<br><br>
     * 
     * Notice: Do not call this function directly. To execute this, call {@link #start()}.
     * This function is merely public because this class extends {@link NativeThread}.
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
        Logger.registerClass(FCPPluginConnectionTracker.class);
    }
}
