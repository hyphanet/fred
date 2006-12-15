/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.oldplugins.plugin;

import java.io.IOException;

import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;

/**
 * Test HTTP plugin. Outputs "Plugin works" to the browser.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class TestHttpPlugin implements HttpPlugin {

	/**
	 * @throws ToadletContextClosedException
	 * @see freenet.oldplugins.plugin.HttpPlugin#handleGet(freenet.clients.http.HTTPRequestImpl)
	 */
	public void handleGet(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException {
		byte[] messageBytes = "Plugin works.".getBytes("UTF-8");
		context.sendReplyHeaders(200, "OK", new MultiValueTable(), "text/html; charset=utf-8", messageBytes.length);
		context.writeData(messageBytes, 0, messageBytes.length);
	}

	/**
	 * @see freenet.oldplugins.plugin.HttpPlugin#handlePost(freenet.clients.http.HTTPRequestImpl)
	 */
	public void handlePost(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException {
	}

	/**
	 * @see freenet.oldplugins.plugin.Plugin#getPluginName()
	 */
	public String getPluginName() {
		return "Simple HTTP Test Plugin";
	}

	/**
	 * @see freenet.oldplugins.plugin.Plugin#setPluginManager(freenet.oldplugins.plugin.PluginManager)
	 */
	public void setPluginManager(PluginManager pluginManager) {
	}

	/**
	 * @see freenet.oldplugins.plugin.Plugin#startPlugin()
	 */
	public void startPlugin() {
	}

	/**
	 * @see freenet.oldplugins.plugin.Plugin#stopPlugin()
	 */
	public void stopPlugin() {
	}

}
