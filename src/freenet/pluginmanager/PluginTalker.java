/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.Node;
import freenet.node.fcp.FCPConnectionHandler;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces
 * 
 */
public class PluginTalker {

	protected Node node;
	protected PluginReplySender replysender;

	protected int access;

	protected FredPluginFCP plugin;
	protected String pluginName;
	protected String connectionIdentifier;

	public PluginTalker(FredPluginTalker fpt, Node node2, String pluginname2, String identifier2) throws PluginNotFoundException {
		node = node2;
		pluginName = pluginname2;
		connectionIdentifier = identifier2;
		plugin = findPlugin(pluginname2);
		access = FredPluginFCP.ACCESS_DIRECT;
		replysender = new PluginReplySenderDirect(node2, fpt, pluginname2, identifier2);
	}

	public PluginTalker(Node node2, FCPConnectionHandler handler, String pluginname2, String identifier2, boolean access2) throws PluginNotFoundException {
		node = node2;
		pluginName = pluginname2;
		connectionIdentifier = identifier2;
		plugin = findPlugin(pluginname2);
		access = access2 ? FredPluginFCP.ACCESS_FCP_FULL : FredPluginFCP.ACCESS_FCP_RESTRICTED;
		replysender = new PluginReplySenderFCP(handler, pluginname2, identifier2);
	}
	
	protected FredPluginFCP findPlugin(String pluginname2) throws PluginNotFoundException {

		Logger.normal(this, "Searching fcp plugin: " + pluginname2);
		FredPluginFCP plug = node.pluginManager.getFCPPlugin(pluginname2);
		if (plug == null) {
			Logger.error(this, "Could not find fcp plugin: " + pluginname2);
			throw new PluginNotFoundException();
		}
		Logger.normal(this, "Found fcp plugin: " + pluginname2);
		return plug;

	}


	public void send(final SimpleFieldSet plugparams, final Bucket data2) {

		node.executor.execute(new Runnable() {

			public void run() {
				plugin.handle(replysender, plugparams, data2, access);
			}
		}, "FCPPlugin talk runner for " + this);

	}
}
