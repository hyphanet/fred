/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.plugins.helpers1;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.PluginRespirator;

public class PluginContext {

	public final PluginRespirator pluginRespirator;
	public final NodeClientCore clientCore;
	public final PageMaker pageMaker;
	public final HighLevelSimpleClient hlsc;
	public final Node node;

	public PluginContext(PluginRespirator pluginRespirator2) {
		this.pluginRespirator = pluginRespirator2;
		this.clientCore = pluginRespirator.getNode().clientCore;
		this.pageMaker = pluginRespirator.getPageMaker();
		this.hlsc = pluginRespirator.getHLSimpleClient();
		this.node = pluginRespirator.getNode();
	}
}
