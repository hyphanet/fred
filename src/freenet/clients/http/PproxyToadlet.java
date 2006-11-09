package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.Date;
import java.util.Iterator;

import freenet.client.HighLevelSimpleClient;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.io.Bucket;

public class PproxyToadlet extends Toadlet {
	private static final int MAX_PLUGIN_NAME_LENGTH = 1024;
	private final PluginManager pm;
	private final NodeClientCore core;

	public PproxyToadlet(HighLevelSimpleClient client, PluginManager pm, NodeClientCore core) {
		super(client);
		this.pm = pm;
		this.core = core;
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {
		
		HTTPRequest request = new HTTPRequest(uri, data, ctx);
		
		MultiValueTable headers = new MultiValueTable();
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable hdrs = new MultiValueTable();
			headers.put("Location", "/queue/");
			ctx.sendReplyHeaders(302, "Found", hdrs, null, 0);
			return;
		}
		
		if (request.isPartSet("load")) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Loading "+request.getPartAsString("load", MAX_PLUGIN_NAME_LENGTH));
			pm.startPlugin(request.getPartAsString("load", MAX_PLUGIN_NAME_LENGTH), true);
			//writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Loading plugin", "Loading plugin...", ".", 5));
	
			headers.put("Location", ".");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}if (request.isPartSet("cancel")){
			headers.put("Location", "/plugins/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}if (request.getPartAsString("unloadconfirm", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
			pm.killPlugin(request.getPartAsString("unloadconfirm", MAX_PLUGIN_NAME_LENGTH));
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Plugins");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-success");
			infobox.addChild("div", "class", "infobox-header", "Plugin unloaded");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			infoboxContent.addChild("#", "The plugin " + request.getPartAsString("remove", MAX_PLUGIN_NAME_LENGTH) + " has been unloaded.");
			infoboxContent.addChild("br");
			infoboxContent.addChild("a", "href", "/plugins/", "Return to Plugin page.");
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		}if (request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Plugins");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-query");
			infobox.addChild("div", "class", "infobox-header", "Unload plugin?");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			infoboxContent.addChild("#", "Are you sure you wish to unload " + request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH) + "?");
			HTMLNode unloadForm = infoboxContent.addChild("form", new String[] { "action", "method" }, new String[] { "/plugins/", "post" });
			unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
			unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
			unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "unloadconfirm", request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH) });
			unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", "Unload" });
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		}else if (request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
			String fn = null;
			Iterator it = pm.getPlugins().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
				if (pi.getThreadName().equals(request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH))) {
					fn = pi.getFilename();
					break;
				}
			}
			
			if (fn == null) {
				this.sendErrorPage(ctx, 404, "Plugin Not Found", "The specified plugin could not be located in order to reload it.");
				//writeReply(ctx, 200, "text/html", "OK", mkForwardPage(ctx,"Error", "Plugin not found...", ".", 5));
			} else {
				pm.killPlugin(request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH));
				pm.startPlugin(fn, true);
				
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			}
			return;
		}else {
			// Ignore
			headers.put("Location", ".");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		}

	}
	
	public void handleGet(URI uri, ToadletContext ctx)
			throws ToadletContextClosedException, IOException {
		//String basepath = "/plugins/";
		HTTPRequest request = new HTTPRequest(uri);
		String path = request.getPath();

		// remove leading / and 'plugins/' from path
		if(path.startsWith("/"))
			path = path.substring(1);
		path = path.substring("plugins/".length());
    	if(Logger.shouldLog(Logger.MINOR, this))
    		Logger.minor(this, "Pproxy fetching "+path);
		try {
			if (path.equals("")) {
				this.showPluginList(ctx, request);
			} else {
				// split path into plugin class name and 'data' path for plugin
				int to = path.indexOf("/");
				String plugin, data;
				if (to == -1) {
					plugin = path;
					data = "";
				} else {
					plugin = path.substring(0, to);
					data = path.substring(to + 1);
				}
				
				//pm.handleHTTPGet(plugin, data);
				
				// create a new request with the 'data' path and pass it to the plugin 
				request = new HTTPRequest(data, uri.getRawQuery());
				//writeReply(ctx, 200, "text/html", "OK", mkPage("plugin", pm.handleHTTPGet(plugin, data)));
				writeReply(ctx, 200, "text/html", "OK", pm.handleHTTPGet(plugin, request));

				
			}
			
			//FetchResult result = fetch(key);
			//writeReply(ctx, 200, result.getMimeType(), "OK", result.asBucket());
			
		} catch (PluginHTTPException ex) {
			// TODO: make it into html
			writeReply(ctx, ex.getCode(), ex.getMimeType(), ex.getDesc(), ex.getReply());
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			String msg = "<html><head><title>Internal Error</title></head><body><h1>Internal Error: please report</h1><pre>";
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			t.printStackTrace(pw);
			pw.flush();
			msg = msg + sw.toString() + "</pre></body></html>";
			this.writeReply(ctx, 500, "text/html", "Internal Error", msg);
		}
	}

	private void showPluginList(ToadletContext ctx, HTTPRequest request) throws ToadletContextClosedException, IOException {
		if (!request.hasParameters()) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Plugins of " + core.getMyName());
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			infobox.addChild("div", "class", "infobox-header", "Plugin list");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			HTMLNode pluginTable = infoboxContent.addChild("table", "class", "plugins");
			HTMLNode headerRow = pluginTable.addChild("tr");
			headerRow.addChild("th", "Classname");
			headerRow.addChild("th", "Internal ID");
			headerRow.addChild("th", "Started at");
			headerRow.addChild("th");
			
			if (pm.getPlugins().isEmpty()) {
				pluginTable.addChild("tr").addChild("td", "colspan", "4", "No plugins loaded");
			}
			else {
				Iterator it = pm.getPlugins().iterator();
				while (it.hasNext()) {
					PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
					HTMLNode pluginRow = pluginTable.addChild("tr");
					pluginRow.addChild("td", pi.getPluginClassName());
					pluginRow.addChild("td", pi.getThreadName());
					pluginRow.addChild("td", new Date(pi.getStarted()).toString());
					HTMLNode actionCell = pluginRow.addChild("td");
					if (pi.isPproxyPlugin()) {
						HTMLNode visitForm = actionCell.addChild("form", new String[] { "method", "action" }, new String[] { "get", pi.getPluginClassName() });
						visitForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
						visitForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Visit" });
					}
					HTMLNode unloadForm = actionCell.addChild("form", new String[] { "action", "method" }, new String[] { ".", "post" });
					unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
					unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "unload", pi.getThreadName() });
					unloadForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Unload" });
					HTMLNode reloadForm = actionCell.addChild("form", new String[] { "action", "method" }, new String[] { ".", "post" });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "reload", pi.getThreadName() });
					reloadForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Reload" });
				}
			}
			
			HTMLNode addForm = infoboxContent.addChild("form", new String[] { "action", "method" }, new String[] { ".", "post" });
			addForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
			HTMLNode loadDiv = addForm.addChild("div");
			loadDiv.addChild("#", "Load plugin: ");
			loadDiv.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "load", "40" });
			loadDiv.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Load" });
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
		} 
	}
}
