/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.lang.ref.WeakReference;

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

    public FCPPluginClientTracker() {
    }

}
