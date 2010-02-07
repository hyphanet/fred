/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.plugins.helpers1;

import java.util.Vector;

import freenet.clients.http.PageMaker;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContainer;
import freenet.pluginmanager.FredPluginL10n;

public class WebInterface {

	private final Vector<WebInterfaceToadlet> _toadlets;
	private final Vector<String> _categories;

	private final ToadletContainer _container;
	private final PageMaker _pageMaker;

	public WebInterface(final PluginContext context) {
		_toadlets = new Vector<WebInterfaceToadlet>();
		_categories = new Vector<String>();
		_container = context.pluginRespirator.getToadletContainer();
		_pageMaker = context.pageMaker;
	}

	public void addNavigationCategory(String uri, String category, String title, FredPluginL10n plugin) {
		_pageMaker.addNavigationCategory(uri, category, title, plugin);
		_categories.add(category);
	}

	public void kill() {
		for (WebInterfaceToadlet toadlet : _toadlets) {
			_container.unregister(toadlet);
		}
		_toadlets.clear();
		for (String category : _categories) {
			_pageMaker.removeNavigationCategory(category);
		}
		_categories.clear();
	}

	public void registerVisible(Toadlet toadlet, String category, String name, String title) {
		_container.register(toadlet, category, toadlet.path(), true, name, title, false, null);
	}

	public void registerInvisible(Toadlet toadlet) {
		_container.register(toadlet , null, toadlet.path(), true, false);
	}
}
