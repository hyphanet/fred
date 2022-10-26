/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.node.Node;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.support.SimpleFieldSet;

/**
 * reload a plugin
 * 
 */
public class ReloadPlugin extends FCPMessage {

	static final String NAME = "ReloadPlugin";

	private final String identifier;
	private final String plugname;
	private final int maxWaitTime;
	private final boolean purge;
	private final boolean store;

	public ReloadPlugin(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Must contain an Identifier field", null, false);
		plugname = fs.get("PluginName");
		if(plugname == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Must contain a PluginName field", identifier, false);
		maxWaitTime = fs.getInt("MaxWaitTime", 0);
		purge = fs.getBoolean("Purge", false);
		store = fs.getBoolean("Store", false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(final FCPConnectionHandler handler, final Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "LoadPlugin requires full access", identifier, false);
		}

		node.executor.execute(new Runnable() {
			@Override
			public void run() {
				PluginInfoWrapper pi = node.pluginManager.getPluginInfo(plugname);
				if (pi == null) {
					handler.send(new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_PLUGIN, false, "Plugin '"+ plugname + "' does not exist or is not a FCP plugin", identifier, false));
				} else {
					String source = pi.getFilename();
					pi.stopPlugin(node.pluginManager, maxWaitTime, true);
					if (purge) {
						node.pluginManager.removeCachedCopy(pi.getFilename());
					}
					pi = node.pluginManager.startPluginAuto(source, store);
					if (pi == null) {
						handler.send(new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_PLUGIN, false, "Plugin '"+ plugname + "' does not exist or is not a FCP plugin", identifier, false));
					} else {
						handler.send(new PluginInfoMessage(pi, identifier, true));
					}
				}
			}
		}, "Reload plugin");
	}

}
