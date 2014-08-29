/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ServerSideFCPMessageHandler;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * <p>Keeps a list of all {@link FCPPluginClient}s which are connected to server plugins running in the node.
 * Allows the server plugins to query a client by its ID.</p>
 * 
 * <p>To understand the purpose of this, please consider the following:<br/>
 * The normal flow of plugin FCP is that clients send messages to a server plugin, and the server plugin immediately sends a reply via the
 * {@link FCPPluginClient} which was passed to its message handling function
 * {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FredPluginFCPMessageHandler.FCPPluginMessage)}.
 * <br/>This might not be sufficient for certain usecases: The reply to a message might take quite some time to compute, possibly hours. Then a reference
 * to the original client needs to be stored in the plugin's database, not memory. <br/>
 * Thus, this class exists to serve the purpose of allowing plugin servers to query clients by their ID (see {@link FCPPluginClient#getID()}).</p>
 * 
 * <p>It is implemented by keeping {@link WeakReference}s to plugin clients, so they only stay in the memory of the tracker as long as they are still
 * connected.</p>
 * 
 * <p>After constructing an object of this class, you must call {@link #start()} to start its garbage collection thread.<br/>
 * For shutdown, no action is required: The thread will be a daemon thread and thus the JVM will deal with shutdown.</p> 
 * 
 * @author xor (xor@freenetproject.org)
 */
final class FCPPluginClientTracker extends NativeThread {
    
    /**
     * Backend table of {@link WeakReference}s to known clients. Monitored by a {@link ReferenceQueue} to automatically remove entries for clients which have
     * been GCed.
     * 
     * Not a {@link ConcurrentHashMap} because the creation of clients is exposed to the FCP network interface and thus DoS would be possible: Java HashMaps
     * never shrink once they have reached a certain size.
     */
    private final TreeMap<UUID, FCPPluginClientWeakReference> clientsByID = new TreeMap<UUID, FCPPluginClientWeakReference>();

    /**
     * Lock to guard {@link #clientsByID} against concurrent modification.
     * A {@link ReadWriteLock} because the usage pattern is mostly reads, very few writes - {@link ReadWriteLock} can do that faster than a regular Lock.
     * (A {@link ReentrantReadWriteLock} because thats the only implementation of {@link ReadWriteLock}.)
     */
    private final ReadWriteLock clientsByIDLock = new ReentrantReadWriteLock();

    /**
     * Queue which monitors removed items of {@link #clientsByID}. Monitored in {@link #realRun()}.
     */
    private final ReferenceQueue<FCPPluginClient> disconnectedClientsQueue = new ReferenceQueue<FCPPluginClient>();

    
    /**
     * We extend class {@link WeakReference} so we can store the ID of the client:<br/>
     * When using a {@link ReferenceQueue} to get notified about nulled {@link WeakReference}s in {@link FCPPluginClientTracker#clientsByID}, we need
     * to remove those {@link WeakReference}s from the {@link TreeMap}. For fast removal, we need their key in the map, which is the client ID, so we should
     * store it in the {@link WeakReference}.
     */
    private static final class FCPPluginClientWeakReference extends WeakReference<FCPPluginClient> {     
        public final UUID clientID;

        public FCPPluginClientWeakReference(FCPPluginClient referent, ReferenceQueue<FCPPluginClient> referenceQueue) {
            super(referent, referenceQueue);
            clientID = referent.getID();
        }
   
    }
    
    /**
     * Stores the {@link FCPPluginClient} so in the future it can be obtained by its ID with {@link FCPPluginClientTracker#getClient(UUID)}.
     * 
     * <b>Must</b> be called for any newly created {@link FCPPluginClient} before passing it to
     * {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}.
     * 
     * Unregistration is not supported and not necessary. 
     */
   void registerClient(FCPPluginClient client) {
        clientsByIDLock.writeLock().lock();
        try {
            // No duplicate checks needed: FCPPluginClient.getID() is a random UUID.
            clientsByID.put(client.getID(), new FCPPluginClientWeakReference(client, disconnectedClientsQueue));
        } finally {
            clientsByIDLock.writeLock().unlock();
        }
    }
    
    /**
     * For being used by implementors of {@link ServerSideFCPMessageHandler}.<br/>
     * NOT for being used by clients: If you are a client using a {@link FCPPluginClient} to connect to a server plugin, you have to keep a reference to
     * the {@link FCPPluginClient} in memory.<br/>
     * This is necessary because this class only keeps {@link WeakReference}s to the {@link FCPPluginClient} objects. Once they are not referenced by a strong
     * reference anymore, they will be garbage collected and thus considered as disconnected.<br/>
     * The job of keeping the strong references is at the client.
     * 
     * @param clientID The ID of{@link FCPPluginClient#getID()} of a client which has already sent a message to your plugin via
     *                 {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}
     * @return The client with the given ID, for as long as it is still connected to the node. 
     * @throws PluginNotFoundException If there has been no client with the given ID or if it has disconnected meanwhile.
     *                                 Notice: The client does not necessarily have to be a plugin. The type of the Exception is similar to
     *                                 PluginNotFoundException so it matches what the send() functions of {@link FCPPluginClient} throw.
     */
    public FCPPluginClient getClient(UUID clientID) throws PluginNotFoundException {
        FCPPluginClientWeakReference ref = null;
        
        clientsByIDLock.readLock().lock();
        try {
            ref = clientsByID.get(clientID);
        } finally {
            clientsByIDLock.readLock().unlock();    
        }
        
        FCPPluginClient client = ref != null ? ref.get() : null;
        
        if(client == null)
            throw new PluginNotFoundException();
        
        return client;
    }


    /**
     * You must call {@link #start()} afterwards!
     */
    public FCPPluginClientTracker() {
        super("FCPPluginClientTracker Garbage-collector", NativeThread.PriorityLevel.MIN_PRIORITY.value, true);
        setDaemon(true);
    }

    /**
     * Garbage-collection thread: Pools {@link #disconnectedClientsQueue} for clients whose {@link WeakReference} has been nulled and removes them from the
     * {@link #clientsByID} {@link TreeMap}.
     */
    public void realRun() {
        try {
            FCPPluginClientWeakReference disconnectedClient = (FCPPluginClientWeakReference)disconnectedClientsQueue.remove();
            clientsByIDLock.writeLock().lock();
            try {
                FCPPluginClientWeakReference removedFromTree = clientsByID.remove(disconnectedClient.clientID);
                
                assert(disconnectedClient == removedFromTree);
                if(logMINOR) {
                    Logger.minor(this, "Garbage-collecting disconnected client: remaining clients = " + clientsByID.size()
                                           + "; client ID = " + disconnectedClient.clientID);
                }
            } finally {
                clientsByIDLock.writeLock().unlock();    
            }
        } catch(InterruptedException e) {
            // We did setDaemon(true), which causes the JVM to exit even if the thread is still running: Daemon threads are force terminated during shutdown.
            // Thus, this thread does not need an exit mechanism, it can be an infinite loop. So nothing should try to terminate it by InterruptedException. 
            // If it does happen nevertheless, we honor it by exiting the thread, because interrupt requests should never be ignored, but log it as an error.
            Logger.error(this, "Thread interruption requested even though this is a daemon thread!", e);
            throw new RuntimeException(e);
        } catch(Throwable t) {
            Logger.error(this, "Error in thread " + getName(), t);
        }
    }


    /** For {@link Logger#registerClass(Class)} */
    private static transient volatile boolean logMINOR = false;
    
    static {
        Logger.registerClass(FCPPluginClientTracker.class);
    }
}
