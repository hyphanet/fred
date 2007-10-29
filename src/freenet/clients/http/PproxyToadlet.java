package freenet.clients.http;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.AccessDeniedPluginHTTPException;
import freenet.pluginmanager.DownloadPluginHTTPException;
import freenet.pluginmanager.NotFoundPluginHTTPException;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.RedirectPluginHTTPException;
import freenet.pluginmanager.PluginManager.PluginProgress;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

public class PproxyToadlet extends Toadlet {
	private static final int MAX_PLUGIN_NAME_LENGTH = 1024;
	/** Maximum time to wait for a threaded plugin to exit */
	private static final int MAX_THREADED_UNLOAD_WAIT_TIME = 60*1000;
	private final Node node;
	private final NodeClientCore core;

	public PproxyToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.node = node;
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
			super.sendErrorPage(ctx, 403, l10n("unauthorizedTitle"), l10n("unauthorized"));
			return;
		}

		String path=request.getPath();

		// remove leading / and plugins/ from path
		if(path.startsWith("/")) path = path.substring(1);
		if(path.startsWith("plugins/")) path = path.substring("plugins/".length());

		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Pproxy received POST on "+path);

		PluginManager pm = node.pluginManager;

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

				writeHTMLReply(ctx, 200, "OK", pm.handleHTTPPost(plugin, request));
			}
			catch (RedirectPluginHTTPException e) {
				writeTemporaryRedirect(ctx, e.message, e.newLocation);
			}
			catch (NotFoundPluginHTTPException e) {
				sendErrorPage(ctx, NotFoundPluginHTTPException.code, e.message, e.location);
			}
			catch (AccessDeniedPluginHTTPException e) {
				sendErrorPage(ctx, AccessDeniedPluginHTTPException.code, e.message, e.location);
			}
			catch (DownloadPluginHTTPException e) {
				// FIXME: maybe it ought to be defined like sendErrorPage : in toadlets

				MultiValueTable head = new MultiValueTable();
				head.put("Content-Disposition", "attachment; filename=\"" + e.filename + '"');
				ctx.sendReplyHeaders(DownloadPluginHTTPException.CODE, "Found", head, e.mimeType, e.data.length);
				ctx.writeData(e.data);
			}
			catch(PluginHTTPException e)
			{
				sendErrorPage(ctx, PluginHTTPException.code, e.message, e.location);
			}
			catch(Throwable t)
			{
				writeInternalError(t, ctx);
			}
		}
		else
		{
			final boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			final boolean logNORMAL = Logger.shouldLog(Logger.NORMAL, this);

			if (request.isPartSet("submit-official") || request.isPartSet("submit-other")) {
				String pluginName = null;
				boolean refresh = request.isPartSet("refresh-on-startup");
				if (request.isPartSet("submit-official")) {
					pluginName = request.getPartAsString("plugin-name", 40);
				} else {
					pluginName = request.getPartAsString("plugin-url", 200);
				}
				pm.startPlugin(pluginName, refresh, true);
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.isPartSet("dismiss-user-alert")) {
				int userAlertHashCode = request.getIntPart("disable", -1);
				core.alerts.dismissAlert(userAlertHashCode);
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.isPartSet("cancel")){
				headers.put("Location", "/plugins/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.getPartAsString("unloadconfirm", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				pm.killPlugin(request.getPartAsString("unloadconfirm", MAX_PLUGIN_NAME_LENGTH), MAX_THREADED_UNLOAD_WAIT_TIME);
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("plugins"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-success");
				infobox.addChild("div", "class", "infobox-header", l10n("pluginUnloaded"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("pluginUnloadedWithName", "name", request.getPartAsString("remove", MAX_PLUGIN_NAME_LENGTH)));
				infoboxContent.addChild("br");
				infoboxContent.addChild("a", "href", "/plugins/", l10n("returnToPluginPage"));
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
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
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}else if (request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				String fn = null;
				boolean refresh = false;
				Iterator it = pm.getPlugins().iterator();
				while (it.hasNext()) {
					PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
					if (pi.getThreadName().equals(request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH))) {
						fn = pi.getFilename();
						refresh = pi.isAutoRefresh();
						break;
					}
				}

				if (fn == null) {
					sendErrorPage(ctx, 404, l10n("pluginNotFoundReloadTitle"), 
							L10n.getString("PluginToadlet.pluginNotFoundReload"));
				} else {
					pm.killPlugin(request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH), MAX_THREADED_UNLOAD_WAIT_TIME);
					pm.startPlugin(fn, refresh, true);

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

		PluginManager pm = node.pluginManager;

		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Pproxy fetching "+path);
		try {
			if (path.equals("")) {
				if (!ctx.isAllowedFullAccess()) {
					super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
					return;
				}

				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("pluginsWithNodeName", "name", core.getMyName()), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

				contentNode.addChild(core.alerts.createSummary());

				UserAlert[] userAlerts = core.alerts.getAlerts();
				for (int index = 0, count = userAlerts.length; index < count; index++) {
					UserAlert userAlert = userAlerts[index];
					if (userAlert.isValid() && (userAlert.getUserIdentifier() == PluginManager.class)) {
						contentNode.addChild(core.alerts.renderAlert(userAlert));
					}
				}

				this.showStartingPlugins(ctx, request, pm, contentNode);
				this.showPluginList(ctx, request, pm, contentNode);

				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
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
				writeHTMLReply(ctx, 200, "OK", pm.handleHTTPGet(plugin, request));				
			}

			//FetchResult result = fetch(key);
			//writeReply(ctx, 200, result.getMimeType(), "OK", result.asBucket());
		} catch (RedirectPluginHTTPException e) {
			writeTemporaryRedirect(ctx, e.message, e.newLocation);
		} catch (NotFoundPluginHTTPException e) {
			sendErrorPage(ctx, NotFoundPluginHTTPException.code, e.message, e.location);
		} catch (AccessDeniedPluginHTTPException e) {
			sendErrorPage(ctx, AccessDeniedPluginHTTPException.code, e.message, e.location);
		} catch (DownloadPluginHTTPException e) {
			// FIXME: maybe it ought to be defined like sendErrorPage : in toadlets

			MultiValueTable head = new MultiValueTable();
			head.put("Content-Disposition", "attachment; filename=\"" + e.filename + '"');
			ctx.sendReplyHeaders(DownloadPluginHTTPException.CODE, "Found", head, e.mimeType, e.data.length);
			ctx.writeData(e.data);
		} catch(PluginHTTPException e) {
			sendErrorPage(ctx, PluginHTTPException.code, e.message, e.location);
		} catch (Throwable t) {
			ctx.forceDisconnect();
			writeInternalError(t, ctx);
		}
	}

	/**
	 * Shows a list of all currently loading plugins.
	 * 
	 * @param toadletContext
	 *            The toadlet context
	 * @param request
	 *            The HTTP request
	 * @param pluginManager
	 *            The plugin manager
	 * @throws ToadletContextClosedException
	 *             if the toadlet context is closed
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	private void showStartingPlugins(ToadletContext toadletContext, HTTPRequest request, PluginManager pluginManager, HTMLNode contentNode) throws ToadletContextClosedException, IOException {
		Set/*<PluginProgress>*/ startingPlugins = pluginManager.getStartingPlugins();
		if (!startingPlugins.isEmpty()) {
			HTMLNode startingPluginsBox = contentNode.addChild("div", "class", "infobox infobox-normal");
			startingPluginsBox.addChild("div", "class", "infobox-header", l10n("startingPluginsTitle"));
			HTMLNode startingPluginsContent = startingPluginsBox.addChild("div", "class", "infobox-content");
			HTMLNode startingPluginsTable = startingPluginsContent.addChild("table");
			HTMLNode startingPluginsHeader = startingPluginsTable.addChild("tr");
			startingPluginsHeader.addChild("th", l10n("startingPluginName"));
			startingPluginsHeader.addChild("th", l10n("startingPluginStatus"));
			startingPluginsHeader.addChild("th", l10n("startingPluginTime"));
			Iterator/*<PluginProgress>*/ startingPluginsIterator = startingPlugins.iterator();
			while (startingPluginsIterator.hasNext()) {
				PluginProgress pluginProgress = (PluginProgress) startingPluginsIterator.next();
				HTMLNode startingPluginsRow = startingPluginsTable.addChild("tr");
				startingPluginsRow.addChild("td", pluginProgress.getName());
				startingPluginsRow.addChild("td", l10n("startingPluginStatus." + pluginProgress.getProgress().toString()));
				startingPluginsRow.addChild("td", "aligh", "right", TimeUtil.formatTime(pluginProgress.getTime()));
			}
		}
	}

	private void showPluginList(ToadletContext ctx, HTTPRequest request, PluginManager pm, HTMLNode contentNode) throws ToadletContextClosedException, IOException {
		if (!request.hasParameters()) {
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
					if(pi.isStopping()) {
						actionCell.addChild("#", l10n("pluginStopping"));
					} else {
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
			}

			/* box for "official" plugins. */
			HTMLNode addOfficialPluginBox = contentNode.addChild("div", "class", "infobox infobox-normal");
			addOfficialPluginBox.addChild("div", "class", "infobox-header", l10n("loadOfficialPlugin"));
			HTMLNode addOfficialPluginContent = addOfficialPluginBox.addChild("div", "class", "infobox-content");
			HTMLNode addOfficialForm = ctx.addFormChild(addOfficialPluginContent, ".", "addOfficialPluginForm");
			addOfficialForm.addChild("div", l10n("loadOfficialPluginText"));
			addOfficialForm.addChild("#", (l10n("loadOfficialPluginLabel") + ": "));
			addOfficialForm.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "plugin-name", "40" });
			addOfficialForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "checkbox", "refresh-on-startup", "true", "checked" });
			addOfficialForm.addChild("#", l10n("refreshOnStartup"));
			addOfficialForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-official", l10n("Load") });

			/* box for unofficial plugins. */
			HTMLNode addOtherPluginBox = contentNode.addChild("div", "class", "infobox infobox-normal");
			addOtherPluginBox.addChild("div", "class", "infobox-header", l10n("loadOtherPlugin"));
			HTMLNode addOtherPluginContent = addOtherPluginBox.addChild("div", "class", "infobox-content");
			HTMLNode addOtherForm = ctx.addFormChild(addOtherPluginContent, ".", "addOtherPluginForm");
			addOtherForm.addChild("div", l10n("loadOtherPluginText"));
			addOtherForm.addChild("#", (l10n("loadOtherURLLabel") + ": "));
			addOtherForm.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "plugin-url", "80" });
			addOtherForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "refresh-on-startup", "true" });
			addOtherForm.addChild("#", l10n("refreshOnStartup"));
			addOtherForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-other", l10n("Load") });
		} 
	}
}
