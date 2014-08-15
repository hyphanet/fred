/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import freenet.pluginmanager.FredPluginFCPServer;

/**
 * <p>Keeps a list of all {@link FCPPluginClient}s which are connected to server plugins running in the node.</p>
 * 
 * <p>To understand the purpose of this, please consider the following:<br/>
 * The normal flow of plugin FCP is that clients send messages to a server plugin, and the server plugin immediately sends a reply via the
 * {@link FCPPluginClient} which was passed to its message handling function
 * {@link FredPluginFCPServer#handleFCPPluginClientMessage(FCPPluginClient, freenet.pluginmanager.FredPluginFCPServer.ClientPermissions, String, freenet.support.SimpleFieldSet, freenet.support.api.Bucket)}.
 * <br/>This might not be sufficient for certain usecases: The reply to a message might take quite some time to compute, possibly hours. Then a reference
 * to the original client needs to be stored in the plugin's database, not memory. <br/>
 * Thus, this class exists to serve the purpose of allowing plugin servers to query clients by their ID (see {@link FCPPluginClient#getID()}).</p>
 * 
 * <p>It is implemented by keeping {@link WeakReference}s to plugin clients, so they only stay in the memory of the tracker as long as they are still
 * connected.</p>
 * 
 * FIXME: Implement similar to class plugins.WebOfTrust.ui.fcp.FCPInterface.ClientTrackerDaemon. Keep an object of FCPPluginClientTracker at FCPServer and
 * add public interface functions registerFCPPluginClient(), getFCPPluginClient().
 * 
 * @author xor (xor@freenetproject.org)
 */
public class FCPPluginClientTracker {
    
    /**
     * Backend table of {@link WeakReference}s to known clients. Monitored by a {@link ReferenceQueue} to automatically remove entries for clients which have
     * been GCed.
     * 
     * Not a {@link ConcurrentHashMap} because the creation of clients is exposed to the FCP network interface and thus DoS would be possible: Java HashMaps
     * never shrink once they have reached a certain size.
     */
    private final TreeMap<UUID, FCPPluginClientWeakReference> clientsByID = new TreeMap<UUID, FCPPluginClientWeakReference>();
    
    
    /**
     * We extend class {@link WeakReference} so we can store the ID of the client:<br/>
     * When using a {@link ReferenceQueue} to get notified about nulled {@link WeakReference}s in {@link FCPPluginClientTracker#clientsByID}, we need
     * to remove those {@link WeakReference}s from the {@link TreeMap}. For fast removal, we need their key in the map, which is the client ID, so we should
     * store it in the {@link WeakReference}.
     */
    private static final class FCPPluginClientWeakReference extends WeakReference<FCPPluginClient> {     
        public final UUID clientID;

        public FCPPluginClientWeakReference(FCPPluginClient referent) {
            super(referent);
            clientID = referent.getID();
        }
   
    }
    
    public FCPPluginClientTracker() {

    }

}
