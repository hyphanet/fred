package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.FredPluginL10n;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public final class PageMaker {
	
	public enum THEME {
		BOXED("boxed", "Boxed", ""),
		CLEAN("clean", "Clean", "Mr. Proper"),
		GRAYANDBLUE("grayandblue", "Gray And Blue", ""),
		SKY("sky", "Sky", ""),
                MINIMALBLUE("minimalblue", "Minimal Blue", "A minimalistic theme in blue");
		
		public static final String[] possibleValues = {
			BOXED.code,
			CLEAN.code,
			GRAYANDBLUE.code,
			SKY.code,
                        MINIMALBLUE.code
		};
		
		public final String code;  // the internal name
		public final String name;  // the name in "human form"
		public final String description; // description
		
		private THEME(String code, String name, String description) {
			this.code = code;
			this.name = name;
			this.description = description;
		}

		public static THEME themeFromName(String cssName) {
			for(THEME t : THEME.values()) {
				if(t.code.equalsIgnoreCase(cssName) ||
				   t.name.equalsIgnoreCase(cssName))
				{
					return t;
				}
			}
			return getDefault();
		}

		public static THEME getDefault() {
			return THEME.CLEAN;
		}
	}	
	
	public static final int MODE_SIMPLE = 1;
	public static final int MODE_ADVANCED = 2;
	private THEME theme;
	private File override;
	private final List<String> navigationLinkTexts = new ArrayList<String>();
	private final List<String> navigationLinkTextsNonFull = new ArrayList<String>();
	private final Map<String, String> navigationLinkTitles = new HashMap<String, String>();
	private final Map<String, String> navigationLinks = new HashMap<String, String>();
	private final Map<HTMLNode, HTMLNode> contentNodes = new HashMap<HTMLNode, HTMLNode>();
	private final Map<HTMLNode, HTMLNode> headNodes = new HashMap<HTMLNode, HTMLNode>();
	private final Map<String, LinkEnabledCallback>  navigationLinkCallbacks = new HashMap<String, LinkEnabledCallback>();
	
	private final FredPluginL10n plugin; 
	private final boolean pluginMode;
	
	public PageMaker(FredPluginL10n plug, THEME t) {
		setTheme(t);
		plugin = plug;
		pluginMode = true;
	}
	
	protected PageMaker(THEME t) {
		setTheme(t);
		plugin = null;
		pluginMode = false;
	}
	
	void setOverride(File f) {
		this.override = f;
	}
	
	public void setTheme(THEME theme2) {
		if (theme2 == null) {
			this.theme = THEME.getDefault();
		} else {
			URL themeurl = getClass().getResource("staticfiles/themes/" + theme2.code + "/theme.css");
			if (themeurl == null)
				this.theme = THEME.getDefault();
			else
				this.theme = theme2;
		}
	}
        	
	public void addNavigationLink(String path, String name, String title, boolean fullOnly, LinkEnabledCallback cb) {
		if (pluginMode && (plugin == null)) {
			// FIXME invent a check on compile time
			// log only
			Logger.error(this, "Deprecated way to use Pagemaker from plugin, your plugin need to implement FredPluginL10n to do so", new Error("FIXME"));
		}
		navigationLinkTexts.add(name);
		if(!fullOnly)
			navigationLinkTextsNonFull.add(name);
		navigationLinkTitles.put(name, title);
		navigationLinks.put(name, path);
		if(cb != null)
			navigationLinkCallbacks.put(name, cb);
	}
	
	/* FIXME: Implement a proper way for chosing what the menu looks like upon handleHTTPGet/Post */
	@Deprecated
	public void removeNavigationLink(String name) {
		navigationLinkTexts.remove(name);
		navigationLinkTextsNonFull.remove(name);
		navigationLinkTitles.remove(name);
		navigationLinks.remove(name);
	}
	
	public HTMLNode createBackLink(ToadletContext toadletContext, String name) {
		String referer = toadletContext.getHeaders().get("referer");
		if (referer != null) {
			return new HTMLNode("a", new String[] { "href", "title" }, new String[] { referer, name }, name);
		}
		return new HTMLNode("a", new String[] { "href", "title" }, new String[] { "javascript:back()", name }, name);
	}
	
	public HTMLNode getPageNode(String title, ToadletContext ctx) {
		return getPageNode(title, true, ctx);
	}

	public HTMLNode getPageNode(String title, boolean renderNavigationLinks, ToadletContext ctx) {
		boolean fullAccess = ctx == null ? false : ctx.isAllowedFullAccess();
		HTMLNode pageNode = new HTMLNode.HTMLDoctype("html", "-//W3C//DTD XHTML 1.1//EN");
		HTMLNode htmlNode = pageNode.addChild("html", "xml:lang", L10n.getSelectedLanguage().isoCode);
		HTMLNode headNode = htmlNode.addChild("head");
		headNode.addChild("meta", new String[] { "http-equiv", "content" }, new String[] { "Content-Type", "text/html; charset=utf-8" });
		headNode.addChild("title", title + " - Freenet");
		if(override == null)
			headNode.addChild("link", new String[] { "rel", "href", "type", "title" }, new String[] { "stylesheet", "/static/themes/" + theme.code + "/theme.css", "text/css", theme.code });
		else
			headNode.addChild(getOverrideContent());
		for (THEME t: THEME.values()) {
			String themeName = t.code;
			headNode.addChild("link", new String[] { "rel", "href", "type", "media", "title" }, new String[] { "alternate stylesheet", "/static/themes/" + themeName + "/theme.css", "text/css", "screen", themeName });
		}
		
		HTMLNode bodyNode = htmlNode.addChild("body");
		HTMLNode pageDiv = bodyNode.addChild("div", "id", "page");
		HTMLNode topBarDiv = pageDiv.addChild("div", "id", "topbar");
		topBarDiv.addChild("h1", title);
		if (renderNavigationLinks) {
			HTMLNode navbarDiv = pageDiv.addChild("div", "id", "navbar");
			HTMLNode navbarUl = navbarDiv.addChild("ul", "id", "navlist");
			for (String navigationLink :  fullAccess ? navigationLinkTexts : navigationLinkTextsNonFull) {
				LinkEnabledCallback cb = navigationLinkCallbacks.get(navigationLink);
				if(cb != null && !cb.isEnabled(ctx)) continue;
				String navigationTitle = navigationLinkTitles.get(navigationLink);
				String navigationPath = navigationLinks.get(navigationLink);
				HTMLNode listItem = navbarUl.addChild("li");
				if (plugin != null)
					listItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, plugin.getString(navigationTitle) }, plugin.getString(navigationLink));
				else
					listItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, L10n.getString(navigationTitle) }, L10n.getString(navigationLink));
			}
		}
		HTMLNode contentDiv = pageDiv.addChild("div", "id", "content");
		headNodes.put(pageNode, headNode);
		contentNodes.put(pageNode, contentDiv);
		return pageNode;
	}

	/**
	 * Returns the head node belonging to the given page node. This method has
	 * to be called before {@link #getContentNode(HTMLNode)}!
	 * 
	 * @param pageNode
	 *            The page node to retrieve the head node for
	 * @return The head node, or <code>null</code> if <code>pageNode</code>
	 *         is not a valid page node or {@link #getContentNode(HTMLNode)} has
	 *         already been called
	 */
	public HTMLNode getHeadNode(HTMLNode pageNode) {
		return headNodes.remove(pageNode);
	}

	/**
	 * Returns the content node that belongs to the specified node. The node has
	 * to be a node that was earlier retrieved by a call to one of the
	 * {@link #getPageNode(String, ToadletContext)} or
	 * {@link #getInfobox(String, String)} methods!
	 * <p>
	 * <strong>Warning:</strong> this method can only be called once!
	 * 
	 * @param node
	 *            The page node to get the content node for
	 * @return The content node for the specified page node
	 */
	public HTMLNode getContentNode(HTMLNode node) {
		headNodes.remove(node);
		return contentNodes.remove(node);
	}
	
	public HTMLNode getInfobox(String header) {
		if (header == null) throw new NullPointerException();
		return getInfobox(new HTMLNode("#", header));
	}
	
	public HTMLNode getInfobox(HTMLNode header) {
		if (header == null) throw new NullPointerException();
		return getInfobox(null, header);
	}

	public HTMLNode getInfobox(String category, String header) {
		if (header == null) throw new NullPointerException();
		return getInfobox(category, new HTMLNode("#", header));
	}

	/**
	 * Returns an infobox with the given style and header. If you retrieve an
	 * infobox from this method, be sure to retrieve the matching content node
	 * with {@link #getContentNode(HTMLNode)} otherwise your layout will be
	 * destroyed (and you will get memory leaks).
	 * 
	 * @param category
	 *            The CSS styles, separated by a space (' ')
	 * @param header
	 *            The header HTML node
	 * @return The infobox
	 */
	public HTMLNode getInfobox(String category, HTMLNode header) {
		if (header == null) throw new NullPointerException();
		HTMLNode infobox = new HTMLNode("div", "class", "infobox" + ((category == null) ? "" : (' ' + category)));
		infobox.addChild("div", "class", "infobox-header").addChild(header);
		contentNodes.put(infobox, infobox.addChild("div", "class", "infobox-content"));
		return infobox;
	}
	
	private HTMLNode getOverrideContent() {
		HTMLNode result = new HTMLNode("style", "type", "text/css");
		
		try {
			result.addChild("#", FileUtil.readUTF(override));
		} catch (IOException e) {
			Logger.error(this, "Got an IOE: " + e.getMessage(), e);
		}
		
		return result;
	}
	
	protected int drawModeSelectionArray(NodeClientCore core, HTTPRequest req, HTMLNode contentNode) {
		return drawModeSelectionArray(core, req, contentNode, -1, null, null);
	}
	
	protected int drawModeSelectionArray(NodeClientCore core, HTTPRequest req, HTMLNode contentNode, int alternateMode, String alternateModeTitleKey, String alternateModeTooltipKey) {
		// Mode can be changed by a link, not just by the default
		
		int mode = core.isAdvancedModeEnabled() ? MODE_ADVANCED : MODE_SIMPLE;
		
		if(req.isParameterSet("mode")) {
			mode = req.getIntParam("mode", mode);
		}
		
		// FIXME style this properly
		HTMLNode table = contentNode.addChild("table", "border", "1");
		HTMLNode row = table.addChild("tr");
		HTMLNode cell = row.addChild("td");
		
		if(alternateMode > -1) {
			if(mode != alternateMode)
				cell.addChild("a", new String[] { "href", "title" }, new String[] { "?mode="+alternateMode, L10n.getString(alternateModeTooltipKey) }, L10n.getString(alternateModeTitleKey));
			else
				cell.addChild("b", "title", L10n.getString(alternateModeTooltipKey), L10n.getString(alternateModeTitleKey));
			cell = row.addChild("td");
		}
		
		if(mode != MODE_SIMPLE)
			cell.addChild("a", new String[] { "href", "title" }, new String[] { "?mode=1", l10n("modeSimpleTooltip") }, l10n("modeSimple"));
		else
			cell.addChild("b", "title", l10n("modeSimpleTooltip"), l10n("modeSimple"));
		cell = row.addChild("td");
		if(mode != MODE_ADVANCED)
			cell.addChild("a", new String[] { "href", "title" }, new String[] { "?mode=2", l10n("modeAdvancedTooltip") }, l10n("modeAdvanced"));
		else
			cell.addChild("b", "title", l10n("modeAdvancedTooltip"), l10n("modeAdvanced"));
		return mode;
	}
	
	private static final String l10n(String string) {
		return L10n.getString("PageMaker." + string);
	}
}
