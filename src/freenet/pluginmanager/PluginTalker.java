/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.lang.ref.WeakReference;
import java.util.UUID;

import freenet.clients.fcp.FCPConnectionHandler;
import freenet.clients.fcp.FCPPluginConnection;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces, xor
 * 
 * @deprecated Use {@link FCPPluginConnection} instead.
 */
@Deprecated
public class PluginTalker {

	protected Node node;
	protected PluginReplySender replysender;

	protected int access;

	protected WeakReference<FredPluginFCP> pluginRef;
	protected String pluginName;

	public PluginTalker(FredPluginTalker fpt, Node node2, String pluginname2, String clientSideIdentifier) throws PluginNotFoundException {
		node = node2;
		pluginName = pluginname2;
		pluginRef = findPlugin(pluginname2);
		access = FredPluginFCP.ACCESS_DIRECT;
		
		// Normally, the clientIdentifier passed to the PluginReplySenderDirect() shall be an identifier of the particular network connection to the client.
		// But this PluginTalker constructor typically gets called by PluginRespirator.getPluginTalker() which is called directly by client plugins.
		// So there is no real FCP network connection, the client plugin runs in the same node as the server plugin.
		// As we have no network connection to pull an ID from, we assume a new client for each call of this constructor by computing a random clientIdentifier.
		final String clientIdentifier = UUID.randomUUID().toString();
		
		replysender = new PluginReplySenderDirect(node2, fpt, pluginname2, clientIdentifier, clientSideIdentifier);
	}

	public PluginTalker(Node node2, FCPConnectionHandler handler, String pluginname2, String clientSideIdentifier, boolean access2) throws PluginNotFoundException {
		node = node2;
		pluginName = pluginname2;
		pluginRef = findPlugin(pluginname2);
		access = access2 ? FredPluginFCP.ACCESS_FCP_FULL : FredPluginFCP.ACCESS_FCP_RESTRICTED;
		
		// FCPConnectionHandler.connectionIdentifier is unique for each network connection of a client, which is exactly what the PluginReplySenderFCP() wants.
		final String clientIdentifier = handler.connectionIdentifier;
		
		replysender = new PluginReplySenderFCP(handler, pluginname2, clientIdentifier, clientSideIdentifier);
	}

	protected WeakReference<FredPluginFCP> findPlugin(String pluginname2) throws PluginNotFoundException {
		Logger.normal(this, "Searching fcp plugin: " + pluginname2);
		FredPluginFCP plug = node.pluginManager.getFCPPlugin(pluginname2);
		if (plug == null) {
			Logger.error(this, "Could not find fcp plugin: " + pluginname2);
			throw new PluginNotFoundException();
		}
		Logger.normal(this, "Found fcp plugin: " + pluginname2);
		return new WeakReference<FredPluginFCP>(plug);
	}

	public void send(final SimpleFieldSet plugparams, final Bucket data2) {

		node.executor.execute(new Runnable() {

			@Override
			public void run() {
				sendSyncInternalOnly(plugparams, data2);
			}
		}, "FCPPlugin talk runner for " + pluginName);
	}
	
	public void sendSyncInternalOnly(final SimpleFieldSet plugparams, final Bucket data2) {
		try {
			FredPluginFCP plug = pluginRef.get();
			if (plug==null) {
				// FIXME How to get this out to surrounding send(..)?
				// throw new PluginNotFoundException(How to get this out to surrounding send(..)?);
				Logger.error(this, "Connection to plugin '"+pluginName+"' lost.", new Exception("FIXME"));
				return;
			}
			plug.handle(replysender, plugparams, data2, access);
		} catch (ThreadDeath td) {
			throw td;  // Fatal, thread is stop()'ed
		} catch (VirtualMachineError vme) {
			throw vme; // OOM is included here
		} catch (Throwable t) {
			Logger.error(this, "Cought error while execute fcp plugin handler for '"+pluginName+"', report it to the plugin author: " + t.getMessage(), t);
		}
	}
}
