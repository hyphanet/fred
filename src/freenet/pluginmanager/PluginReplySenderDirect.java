/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.clients.fcp.FCPPluginConnection;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces
 * @author xor (xor@freenetproject.org)
 * @deprecated Use the {@link FCPPluginConnection} API instead.
 */
@Deprecated
public class PluginReplySenderDirect extends PluginReplySender {
	
	private final Node node;
	private final FredPluginTalker target;

	/**
	 * @see PluginReplySender#PluginReplySender(String, String, String)
	 */
	public PluginReplySenderDirect(Node node2, FredPluginTalker target2, String pluginname2, String clientIdentifier, String clientSideIdentifier) {
		super(pluginname2, clientIdentifier, clientSideIdentifier);
		node = node2;
		target = target2;
	}

	@Override
	public void send(final SimpleFieldSet params, final Bucket bucket) {
		
		node.executor.execute(new Runnable() {

			@Override
			public void run() {

				try {
					target.onReply(pluginname, clientSideIdentifier, params, bucket);
				} catch (Throwable t) {
					Logger.error(this, "Cought error while handling plugin reply: " + t.getMessage(), t);
				}

			}
		}, "FCPPlugin reply runner for " + pluginname);
	}

}
