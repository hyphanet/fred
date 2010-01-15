/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.plugins.helpers1;

import freenet.clients.http.Toadlet;

public class InvisibleWebInterfaceToadlet extends WebInterfaceToadlet {

	private final Toadlet _showAsToadlet;

	protected InvisibleWebInterfaceToadlet(PluginContext pluginContext2,
			String pluginURL2, String pageName2, Toadlet showAsToadlet) {
		super(pluginContext2, pluginURL2, pageName2);
		_showAsToadlet = showAsToadlet;
	}

	@Override
	public Toadlet showAsToadlet() {
		return _showAsToadlet;
	}
}
