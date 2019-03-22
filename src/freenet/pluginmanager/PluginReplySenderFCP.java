/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.clients.fcp.FCPConnectionHandler;
import freenet.clients.fcp.FCPPluginConnection;
import freenet.clients.fcp.FCPPluginServerMessage;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces
 * @author xor (xor@freenetproject.org)
 * @deprecated Use the {@link FCPPluginConnection} API instead.
 */
@Deprecated
public class PluginReplySenderFCP extends PluginReplySender {
	
	final FCPConnectionHandler handler; 

	/**
	 * @see PluginReplySender#PluginReplySender(String, String, String)
	 */
	public PluginReplySenderFCP(FCPConnectionHandler handler2, String pluginname2, String clientIdentifier, String clientSideIdentifier) {
		super(pluginname2, clientIdentifier, clientSideIdentifier);
		
		handler = handler2;
	}

	@Override
	public void send(SimpleFieldSet params, Bucket bucket) throws PluginNotFoundException {
		// like in linux everthing is a file, in Plugintalker everything is a plugin. So it throws PluginNotFoundException
		// instead fcp connection errors 
		if (handler.isClosed()) throw new PluginNotFoundException("FCP connection closed");
        FCPPluginServerMessage reply = new FCPPluginServerMessage(pluginname, clientSideIdentifier, params, bucket);
		handler.send(reply);
	}
}
