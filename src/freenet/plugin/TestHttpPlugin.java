/*
  TestHttpPlugin.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  Copyright (C) 2006 David 'Bombe' Roden <bombe@freenetproject.org>
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.plugin;

import java.io.IOException;

import freenet.clients.http.HTTPRequest;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.MultiValueTable;

/**
 * Test HTTP plugin. Outputs "Plugin works" to the browser.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class TestHttpPlugin implements HttpPlugin {

	/**
	 * @throws ToadletContextClosedException
	 * @see freenet.plugin.HttpPlugin#handleGet(freenet.clients.http.HTTPRequest)
	 */
	public void handleGet(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException {
		byte[] messageBytes = "Plugin works.".getBytes("UTF-8");
		context.sendReplyHeaders(200, "OK", new MultiValueTable(), "text/html; charset=utf-8", messageBytes.length);
		context.writeData(messageBytes, 0, messageBytes.length);
	}

	/**
	 * @see freenet.plugin.HttpPlugin#handlePost(freenet.clients.http.HTTPRequest)
	 */
	public void handlePost(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException {
	}

	/**
	 * @see freenet.plugin.Plugin#getPluginName()
	 */
	public String getPluginName() {
		return "Simple HTTP Test Plugin";
	}

	/**
	 * @see freenet.plugin.Plugin#setPluginManager(freenet.plugin.PluginManager)
	 */
	public void setPluginManager(PluginManager pluginManager) {
	}

	/**
	 * @see freenet.plugin.Plugin#startPlugin()
	 */
	public void startPlugin() {
	}

	/**
	 * @see freenet.plugin.Plugin#stopPlugin()
	 */
	public void stopPlugin() {
	}

}
