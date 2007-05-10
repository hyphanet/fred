package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.Iterator;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.AccessDeniedPluginHTTPException;
import freenet.pluginmanager.DownloadPluginHTTPException;
import freenet.pluginmanager.NotFoundPluginHTTPException;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.RedirectPluginHTTPException;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;

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

	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {

		MultiValueTable headers = new MultiValueTable();

		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable hdrs = new MultiValueTable();
			headers.put("Location", "/queue/");
			ctx.sendReplyHeaders(302, "Found", hdrs, null, 0);
			return;
		}

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", l10n("unauthorized"));
			return;
		}
		
		String path=request.getPath();

		// remove leading / and plugins/ from path
		if(path.startsWith("/")) path = path.substring(1);
		if(path.startsWith("plugins/")) path = path.substring("plugins/".length());

		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Pproxy received POST on "+path);

		if(path.length()>0)
		{
			try
			{
				String plugin = null;
				// split path into plugin class name and 'daa' path for plugin
				int to = path.indexOf("/");
				if(to == -1)
				{
					plugin = path;
				}
				else
				{
					plugin = path.substring(0, to);
				}

				writeReply(ctx, 200, "text/html", "OK", pm.handleHTTPPost(plugin, request));
			}
			catch (RedirectPluginHTTPException e) {
				writeTemporaryRedirect(ctx, e.message, e.newLocation);
			}
			catch (NotFoundPluginHTTPException e) {
				sendErrorPage(ctx, e.code, e.message, e.location);
			}
			catch (AccessDeniedPluginHTTPException e) {
				sendErrorPage(ctx, e.code, e.message, e.location);
			}
			catch (DownloadPluginHTTPException e) {
				// FIXME: maybe it ought to be defined like sendErrorPage : in toadlets

				MultiValueTable head = new MultiValueTable();
				head.put("Content-Disposition", "attachment; filename=\"" + e.filename + '"');
				ctx.sendReplyHeaders(e.code, "Found", head, e.mimeType, e.data.length);
				ctx.writeData(e.data);
			}
			catch(PluginHTTPException e)
			{
				sendErrorPage(ctx, e.code, e.message, e.location);
			}
			catch(Throwable t)
			{
				writeInternalError(t, ctx);
			}
		}
		else
		{

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
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("plugins"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-success");
				infobox.addChild("div", "class", "infobox-header", l10n("pluginUnloaded"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("pluginUnloadedWithName", "name", request.getPartAsString("remove", MAX_PLUGIN_NAME_LENGTH)));
				infoboxContent.addChild("br");
				infoboxContent.addChild("a", "href", "/plugins/", l10n("returnToPluginPage"));
				writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
				return;
			}if (request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("plugins"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-query");
				infobox.addChild("div", "class", "infobox-header", l10n("unloadPluginTitle"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("unloadPluginWithName", "name", request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH)));
				HTMLNode unloadForm = 
					ctx.addFormChild(infoboxContent, "/plugins/", "unloadPluginConfirmForm");
				unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
				unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "unloadconfirm", request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH) });
				unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", l10n("unload") });
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
					sendErrorPage(ctx, 404, l10n("pluginNotFoundReloadTitle"), 
							L10n.getString("PluginToadlet.pluginNotFoundReload"));
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

	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("PproxyToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private String l10n(String key) {
		return L10n.getString("PproxyToadlet."+key);
	}

	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {

		//String basepath = "/plugins/";
		String path = request.getPath();

		// remove leading / and plugins/ from path
		if(path.startsWith("/")) path = path.substring(1);
		if(path.startsWith("plugins/")) path = path.substring("plugins/".length());

		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Pproxy fetching "+path);
		try {
			if (path.equals("")) {
				this.showPluginList(ctx, request);
			} else {
				// split path into plugin class name and 'data' path for plugin
				int to = path.indexOf("/");
				String plugin;
				if (to == -1) {
					plugin = path;
				} else {
					plugin = path.substring(0, to);
				}

				// Plugin may need to know where it was accessed from, so it can e.g. produce relative URLs.
				//writeReply(ctx, 200, "text/html", "OK", mkPage("plugin", pm.handleHTTPGet(plugin, data)));
				writeReply(ctx, 200, "text/html", "OK", pm.handleHTTPGet(plugin, request));				
			}

			//FetchResult result = fetch(key);
			//writeReply(ctx, 200, result.getMimeType(), "OK", result.asBucket());
		} catch (RedirectPluginHTTPException e) {
			writeTemporaryRedirect(ctx, e.message, e.newLocation);
		} catch (NotFoundPluginHTTPException e) {
			sendErrorPage(ctx, e.code, e.message, e.location);
		} catch (AccessDeniedPluginHTTPException e) {
			sendErrorPage(ctx, e.code, e.message, e.location);
		} catch (DownloadPluginHTTPException e) {
			// FIXME: maybe it ought to be defined like sendErrorPage : in toadlets

			MultiValueTable head = new MultiValueTable();
			head.put("Content-Disposition", "attachment; filename=\"" + e.filename + '"');
			ctx.sendReplyHeaders(e.code, "Found", head, e.mimeType, e.data.length);
			ctx.writeData(e.data);
		} catch(PluginHTTPException e) {
			sendErrorPage(ctx, e.code, e.message, e.location);
		} catch (Throwable t) {
			writeInternalError(t, ctx);
		}
	}

	private void showPluginList(ToadletContext ctx, HTTPRequest request) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}

		if (!request.hasParameters()) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("pluginsWithNodeName", "name", core.getMyName()), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			infobox.addChild("div", "class", "infobox-header", L10n.getString("PluginToadlet.pluginListTitle"));
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			HTMLNode pluginTable = infoboxContent.addChild("table", "class", "plugins");
			HTMLNode headerRow = pluginTable.addChild("tr");
			headerRow.addChild("th", l10n("classNameTitle"));
			headerRow.addChild("th", l10n("internalIDTitle"));
			headerRow.addChild("th", l10n("startedAtTitle"));
			headerRow.addChild("th");

			if (pm.getPlugins().isEmpty()) {
				pluginTable.addChild("tr").addChild("td", "colspan", "4", l10n("noPlugins"));
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
						HTMLNode visitForm = actionCell.addChild("form", new String[] { "method", "action", "target" }, new String[] { "get", pi.getPluginClassName(), "_new" });
						visitForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
						visitForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", L10n.getString("PluginToadlet.visit") });
					}
					HTMLNode unloadForm = ctx.addFormChild(actionCell, ".", "unloadPluginForm");
					unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "unload", pi.getThreadName() });
					unloadForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("unload") });
					HTMLNode reloadForm = ctx.addFormChild(actionCell, ".", "reloadPluginForm");
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "reload", pi.getThreadName() });
					reloadForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("reload") });
				}
			}

			HTMLNode addForm = ctx.addFormChild(infoboxContent, ".", "addPluginForm");
			HTMLNode loadDiv = addForm.addChild("div");
			loadDiv.addChild("#", l10n("loadPluginLabel"));
			loadDiv.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "load", "40" });
			loadDiv.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Load" });
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
		} 
	}
}
