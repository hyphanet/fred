/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces
 *
 */
public class PluginReplySenderDirect extends PluginReplySender {
	
	private final Node node;
	private final FredPluginTalker target;

	public PluginReplySenderDirect(Node node2, FredPluginTalker target2, String pluginname2, String identifier2) {
		super(pluginname2, identifier2);
		node = node2;
		target = target2;
	}

	@Override
	public void send(final SimpleFieldSet params, final Bucket bucket) {
		
		node.executor.execute(new Runnable() {

			public void run() {

				try {
					target.onReply(pluginname, identifier, params, bucket);
				} catch (Throwable t) {
					Logger.error(this, "Cought error while handling plugin reply: " + t.getMessage(), t);
				}

			}
		}, "FCPPlugin reply runner for " + pluginname);
	}

}
