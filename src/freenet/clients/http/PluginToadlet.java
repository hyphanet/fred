/**
 * 
 */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.plugin.HttpPlugin;
import freenet.plugin.Plugin;
import freenet.plugin.PluginManager;
import freenet.support.HTMLEncoder;

/**
 * Toadlet for the plugin manager.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class PluginToadlet extends Toadlet {

	/** The plugin manager backing this toadlet. */
	private final PluginManager pluginManager;

	/**
	 * Creates a new toadlet.
	 * 
	 * @param client
	 *            The high-level client to use
	 * @param pluginManager
	 *            The plugin manager to use
	 */
	protected PluginToadlet(HighLevelSimpleClient client, PluginManager pluginManager) {
		super(client);
		this.pluginManager = pluginManager;
	}

	/**
	 * Currently this toadlet only supports GET.
	 * 
	 * @see freenet.clients.http.Toadlet#supportedMethods()
	 * @return "GET"
	 */
	public String supportedMethods() {
		return "GET";
	}

	/**
	 * Handles a GET request.
	 * 
	 * @see freenet.clients.http.Toadlet#handleGet(java.net.URI,
	 *      freenet.clients.http.ToadletContext)
	 * @param uri
	 *            The URI that was requested
	 * @param ctx
	 *            The context of this toadlet
	 */
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		HTTPRequest httpRequest = new HTTPRequest(uri, null, ctx);

		String uriPath = uri.getPath();
		String pluginName = uriPath.substring(uriPath.lastIndexOf('/') + 1);

		if (pluginName.length() > 0) {
			Plugin plugin = findPlugin(pluginName);
			if (plugin != null) {
				if (plugin instanceof HttpPlugin) {
					((HttpPlugin) plugin).handleGet(httpRequest, ctx);
				} else {
					writeReply(ctx, 220, "text/html; charset=utf-8", "OK", createBox(ctx, "Plugin has no web interface", "The plugin does not have a web interface, so there is nothing to show.").toString());
				}
				return;
			}
			writeReply(ctx, 220, "text/html; charset=utf-8", "OK", createBox(ctx, "Plugin not found", "The requested plugin could not be found.").toString());
		}

		String action = httpRequest.getParam("action");
		if (action.length() == 0) {
			writePermanentRedirect(ctx, "Plugin list", "?action=list");
			return;
		}

		StringBuffer replyBuffer = new StringBuffer();
		if ("list".equals(action)) {
			replyBuffer.append(listPlugins(ctx));
		} else if ("add".equals(action)) {
			pluginName = httpRequest.getParam("pluginName");
			boolean added = false;
			try {
				pluginManager.addPlugin(pluginName);
				added = true;
			} catch (IllegalArgumentException iae1) {
			}
			if (added) {
				writePermanentRedirect(ctx, "Plugin list", "?action=list");
				return;
			}
			replyBuffer.append(createBox(ctx, "Plugin was not loaded", "The plugin you requested could not be loaded. Please verify the name of the plugin&rsquo;s class and the URL, if you gave one."));
		} else if ("reload".equals(action)) {
			pluginName = httpRequest.getParam("pluginName");
			Plugin plugin = findPlugin(pluginName);
			plugin.stopPlugin();
			plugin.startPlugin();
			writePermanentRedirect(ctx, "Plugin list", "?action=list");
		} else if ("unload".equals(action)) {
			pluginName = httpRequest.getParam("pluginName");
			Plugin plugin = findPlugin(pluginName);
			pluginManager.removePlugin(plugin);
			writePermanentRedirect(ctx, "Plugin list", "?action=list");
		}
		writeReply(ctx, 220, "text/html; charset=utf-8", "OK", replyBuffer.toString());
	}

	/**
	 * Searches the currently installed plugins for the plugin with the
	 * specified internal name.
	 * 
	 * @param internalPluginName
	 *            The internal name of the wanted plugin
	 * @return The wanted plugin, or <code>null</code> if no plugin could be
	 *         found
	 */
	private Plugin findPlugin(String internalPluginName) {
		Plugin[] plugins = pluginManager.getPlugins();
		for (int pluginIndex = 0, pluginCount = plugins.length; pluginIndex < pluginCount; pluginIndex++) {
			Plugin plugin = plugins[pluginIndex];
			String pluginName = plugin.getClass().getName() + "@" + pluginIndex;
			if (pluginName.equals(internalPluginName)) {
				return plugin;
			}
		}
		return null;
	}

	/**
	 * Creates a complete HTML page containing a list of all plugins.
	 * 
	 * @param context
	 *            The toadlet context
	 * @return A StringBuffer containing the HTML page
	 */
	private StringBuffer listPlugins(ToadletContext context) {
		Plugin[] plugins = pluginManager.getPlugins();
		PageMaker pageMaker = context.getPageMaker();
		StringBuffer outputBuffer = new StringBuffer();
		pageMaker.makeHead(outputBuffer, "List of Plugins", true);

		outputBuffer.append("<div class=\"infobox\">");
		outputBuffer.append("<div class=\"infobox-header\">Plugin list</div>");
		outputBuffer.append("<div class=\"infobox-content\"><table class=\"plugintable\">");
		outputBuffer.append("<tr>");
		outputBuffer.append("<th>Plugin Name</th>");
		outputBuffer.append("<th>Internal Name</th>");
		outputBuffer.append("<th colspan=\"3\" />");
		outputBuffer.append("</tr>\n");
		for (int pluginIndex = 0, pluginCount = plugins.length; pluginIndex < pluginCount; pluginIndex++) {
			Plugin plugin = plugins[pluginIndex];
			String internalName = plugin.getClass().getName() + "@" + pluginIndex;
			outputBuffer.append("<tr>");
			outputBuffer.append("<td>").append(HTMLEncoder.encode(plugin.getPluginName())).append("</td>");
			outputBuffer.append("<td>").append(HTMLEncoder.encode(internalName)).append("</td>");
			if (plugin instanceof HttpPlugin) {
				outputBuffer.append("<td><form action=\"").append(HTMLEncoder.encode(internalName)).append("\" method=\"get\"><input type=\"submit\" value=\"Visit\" /></form></td>");
			} else {
				outputBuffer.append("<td/>");
			}
			outputBuffer.append("<td><form action=\"\" method=\"get\"><input type=\"hidden\" name=\"action\" value=\"reload\"><input type=\"hidden\" name=\"pluginName\" value=\"").append(internalName).append("\" /><input type=\"submit\" value=\"Reload\" /></form></td>");
			outputBuffer.append("<td><form action=\"\" method=\"get\"><input type=\"hidden\" name=\"action\" value=\"unload\"><input type=\"hidden\" name=\"pluginName\" value=\"").append(internalName).append("\" /><input type=\"submit\" value=\"Unload\" /></form></td>");
			outputBuffer.append("</tr>\n");
		}
		outputBuffer.append("</table>");
		outputBuffer.append("</div>\n");
		outputBuffer.append("</div>\n");

		appendAddPluginBox(outputBuffer);

		pageMaker.makeTail(outputBuffer);
		return outputBuffer;
	}

	/**
	 * Creates an alert box with the specified title and message. A link to the
	 * plugin list is added after the message.
	 * 
	 * @param context
	 *            The toadlet context
	 * @param title
	 *            The title of the box
	 * @param message
	 *            The content of the box
	 * @return A StringBuffer containing the complete page
	 */
	private StringBuffer createBox(ToadletContext context, String title, String message) {
		PageMaker pageMaker = context.getPageMaker();
		StringBuffer outputBuffer = new StringBuffer();
		pageMaker.makeHead(outputBuffer, HTMLEncoder.encode(title));
		outputBuffer.append("<div class=\"infobox infobox-alert\">");
		outputBuffer.append("<div class=\"infobox-header\">").append(HTMLEncoder.encode(title)).append("</div>\n");
		outputBuffer.append("<div class=\"infobox-content\">").append(HTMLEncoder.encode(message)).append("</div>\n");
		outputBuffer.append("<div class=\"infobox-content\">Please <a href=\"?action=list\">return</a> to the list of plugins.</div>\n");
		outputBuffer.append("</div>\n");
		pageMaker.makeTail(outputBuffer);

		return outputBuffer;
	}

	/**
	 * Appends the HTML code for the &ldquo;add plugin&rdquo; box to the given
	 * StringBuffer.
	 * 
	 * @param outputBuffer
	 *            The StringBuffer to append the HTML code to
	 */
	private void appendAddPluginBox(StringBuffer outputBuffer) {
		outputBuffer.append("<div class=\"infobox\">");
		outputBuffer.append("<div class=\"infobox-header\">Add a plugin</div>");
		outputBuffer.append("<div class=\"infobox-content\">");
		outputBuffer.append("<form action=\"?add\" method=\"get\">");
		outputBuffer.append("<input type=\"hidden\" name=\"action\" value=\"add\" />");
		outputBuffer.append("<input type=\"text\" size=\"40\" name=\"pluginName\" value=\"\" />&nbsp;");
		outputBuffer.append("<input type=\"submit\" value=\"Load plugin\" />");
		outputBuffer.append("</form>");
		outputBuffer.append("</div>\n");
		outputBuffer.append("</div>\n");
	}

}
