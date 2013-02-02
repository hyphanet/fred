package freenet.clients.http;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import freenet.client.filter.PushingTagReplacerCallback;
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.Node;
import freenet.node.SecurityLevels;
import freenet.pluginmanager.FredPluginL10n;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public final class PageMaker {
	
	public enum THEME {
		BOXED("boxed", "Boxed (Top menu)", "", false, false),
		BOXED_CLASSIC("boxed-classic", "Boxed (Classic menu)", "", false, false),
		BOXED_DROPDOWN("boxed-dropdown", "Boxed (Dropdown menu)", "", false, false),
		BOXED_DYNAMIC("boxed-classic", "Boxed (Dynamic menu)", "", false, false),
		BOXED_STATIC("boxed-static", "Boxed (Static menu)", "", false, false),
		CLEAN("clean", "Clean", "Mr. Proper", false, false),
		CLEAN_CLASSIC("clean-classic", "Clean (Classic menu)", "Clean theme with a classic menu.", false, false),
		CLEAN_DROPDOWN("clean-dropdown", "Clean (Dropdown menu)", "Clean theme with a dropdown menu.", false, false),
		CLEAN_STATIC("clean-static", "Clean (Static menu)", "Clean theme with a static menu.", false, false),
		CLEAN_TOP("clean-top", "Clean (Top menu)", "Clean theme with a static top menu.", false, false),
		GRAYANDBLUE("grayandblue", "Gray And Blue (Classic menu)", "", false, false),
		GRAYANDBLUE_DYNAMIC("grayandblue-dynamic", "Gray And Blue (Dynamic menu)", "", false, false),
		GRAYANDBLUE_DROPDOWN("grayandblue-dropdown", "Gray And Blue (Dropdown menu)", "", false, false),
		GRAYANDBLUE_STATIC("grayandblue-static", "Gray And Blue (Static menu)", "", false, false),
		GRAYANDBLUE_TOP("grayandblue-top", "Gray And Blue (Top menu)", "", false, false),
		SKY("sky", "Sky (Top menu)", "", false, false),
		SKY_CLASSIC("sky-classic", "Sky (Classic menu)", "", false, false),
		SKY_DROPDOWN("sky-dropdown", "Sky (Dropdown menu)", "", false, false),
		SKY_DYNAMIC("sky-dynamic", "Sky (Dynamic menu)", "", false, false),
		SKY_STATIC("sky-static", "Sky (Static menu)", "", false, false),
		MINIMALBLUE("minimalblue", "Minimal Blue", "A minimalistic theme in blue", false, false),
		MINIMALISTIC("minimalist", "Minimalistic", "A very minimalistic theme based on Google's designs", true, true),
		RABBIT_HOLE("rabbit-hole", "Into the Rabbit Hole", "Simple and clean theme", false, false);

		
		public static final String[] possibleValues = {
			BOXED.code,
			BOXED_CLASSIC.code,
			BOXED_DROPDOWN.code,
			BOXED_DYNAMIC.code,
			BOXED_STATIC.code,
			CLEAN.code,
			CLEAN_CLASSIC.code,
			CLEAN_DROPDOWN.code,
			CLEAN_STATIC.code,
			CLEAN_TOP.code,
			GRAYANDBLUE.code,
			GRAYANDBLUE_DYNAMIC.code,
			GRAYANDBLUE_DROPDOWN.code,
			GRAYANDBLUE_STATIC.code,
			GRAYANDBLUE_TOP.code,
			SKY.code,
			SKY_CLASSIC.code,
			SKY_DROPDOWN.code,
			SKY_DYNAMIC.code,
			SKY_STATIC.code,
			MINIMALBLUE.code,
			MINIMALISTIC.code,
			RABBIT_HOLE.code
		};
		
		public final String code;  // the internal name
		public final String name;  // the name in "human form"
		public final String description; // description
		/**
		 * If true, the activelinks will appear on the welcome page, whether
		 * the user has enabled them or not.
		 */
		public final boolean forceActivelinks;
		/**
		 * If true, the "Fetch a key" infobox will appear above the bookmarks
		 * infobox on the welcome page.
		 */
		public final boolean fetchKeyBoxAboveBookmarks;
		
		private THEME(String code, String name, String description) {
			this(code, name, description, false, false);
		}

		private THEME(String code, String name, String description, boolean forceActivelinks, boolean fetchKeyBoxAboveBookmarks) {
			this.code = code;
			this.name = name;
			this.description = description;
			this.forceActivelinks = forceActivelinks;
			this.fetchKeyBoxAboveBookmarks = fetchKeyBoxAboveBookmarks;
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

	/** Parameter for simple/advanced mode switch. */
	private static final String MODE_SWITCH_PARAMETER = "fproxyAdvancedMode";

	private THEME theme;
	private String override;
	private final Node node;
	
	private List<SubMenu> menuList = new ArrayList<SubMenu>();
	private Map<String, SubMenu> subMenus = new HashMap<String, SubMenu>();
	
	private static class SubMenu {
		
		/** Name of the submenu */
		private final String navigationLinkText;
		/** Link if the user clicks on the submenu itself */
		private final String defaultNavigationLink;
		/** Tooltip */
		private final String defaultNavigationLinkTitle;
		
		private final FredPluginL10n plugin;
		
		private final List<String> navigationLinkTexts = new ArrayList<String>();
		private final List<String> navigationLinkTextsNonFull = new ArrayList<String>();
		private final Map<String, String> navigationLinkTitles = new HashMap<String, String>();
		private final Map<String, String> navigationLinks = new HashMap<String, String>();
		private final Map<String, LinkEnabledCallback>  navigationLinkCallbacks = new HashMap<String, LinkEnabledCallback>();
		private final Map<String, FredPluginL10n> navigationLinkL10n = new HashMap<String, FredPluginL10n>();
		
		public SubMenu(String link, String name, String title, FredPluginL10n plugin) {
			this.navigationLinkText = name;
			this.defaultNavigationLink = link;
			this.defaultNavigationLinkTitle = title;
			this.plugin = plugin;
		}

		public void addNavigationLink(String path, String name, String title, boolean fullOnly, LinkEnabledCallback cb, FredPluginL10n l10n) {
			navigationLinkTexts.add(name);
			if(!fullOnly)
				navigationLinkTextsNonFull.add(name);
			navigationLinkTitles.put(name, title);
			navigationLinks.put(name, path);
			if(cb != null)
				navigationLinkCallbacks.put(name, cb);
			if (l10n != null)
				navigationLinkL10n.put(name, l10n);
		}

		@Deprecated
		public void removeNavigationLink(String name) {
			navigationLinkTexts.remove(name);
			navigationLinkTextsNonFull.remove(name);
			navigationLinkTitles.remove(name);
			navigationLinks.remove(name);
			navigationLinkL10n.remove(name); //Should this be here? If so, why not remove from navigationLinkCallbacks too
		}

		@Deprecated
		public void removeAllNavigationLinks() {
			navigationLinkTexts.clear();
			navigationLinkTextsNonFull.clear();
			navigationLinkTitles.clear();
			navigationLinks.clear();
			navigationLinkL10n.clear(); //Should this be here? If so, why not clear navigationLinkCallbacks too
		}
	}
	
	protected PageMaker(THEME t, Node n) {
		setTheme(t);
		this.node = n;
	}
	
	void setOverride(String pointTo) {
		this.override = pointTo;
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

	public synchronized void addNavigationCategory(String link, String name, String title, FredPluginL10n plugin) {
		SubMenu menu = new SubMenu(link, name, title, plugin);
		subMenus.put(name, menu);
		menuList.add(menu);
	}
	
	/**
	 * Add a navigation category to the menu at a given offset.
	 * @param menuOffset The position of the link in FProxy's menu. 0 = left.
	 */
	public synchronized void addNavigationCategory(String link, String name, String title, FredPluginL10n plugin, int menuOffset) {
		SubMenu menu = new SubMenu(link, name, title, plugin);
		subMenus.put(name, menu);
		menuList.add(menuOffset, menu);
	}
	

	public synchronized void removeNavigationCategory(String name) {
		SubMenu menu = subMenus.remove(name);
		if (menu == null) {
			Logger.error(this, "can't remove navigation category, name="+name);
			return;
		}	
		menuList.remove(menu);
	}
	
	public synchronized void addNavigationLink(String menutext, String path, String name, String title, boolean fullOnly, LinkEnabledCallback cb, FredPluginL10n l10n) {
		SubMenu menu = subMenus.get(menutext);
		if(menu == null)
			throw new NullPointerException("there is no menu named "+menutext);
		menu.addNavigationLink(path, name, title, fullOnly, cb, l10n);
	}
	
	/* FIXME: Implement a proper way for chosing what the menu looks like upon handleHTTPGet/Post */
	@Deprecated
	public synchronized void removeNavigationLink(String menutext, String name) {
		SubMenu menu = subMenus.get(menutext);
		menu.removeNavigationLink(name);
	}
	
	@Deprecated
	public synchronized void removeAllNavigationLinks() {
		for(SubMenu menu : subMenus.values())
			menu.removeAllNavigationLinks();
	}
	
	public HTMLNode createBackLink(ToadletContext toadletContext, String name) {
		String referer = toadletContext.getHeaders().get("referer");
		if (referer != null) {
			return new HTMLNode("a", new String[] { "href", "title" }, new String[] { referer, name }, name);
		}
		return new HTMLNode("a", new String[] { "href", "title" }, new String[] { "javascript:back()", name }, name);
	}

	/**
	 * Generates an FProxy template page suitable for adding content to.
	 *
	 * @param title
	 *            Title of the page.
	 * @param ctx
	 *            ToadletContext to use to render the page.
	 * @return A template PageNode.
	 */
	public PageNode getPageNode(String title, ToadletContext ctx) {
		return getPageNode(title, true, ctx);
	}

	/**
	 * Generates an FProxy template page with optional navigation bar suitable
	 * for adding content to.
	 *
	 * @param title
	 *            Title of the page.
	 * @param renderNavigationLinks
	 *            Whether to render navigation links.
	 * @param ctx
	 *            ToadletContext to use to render the page.
	 * @return A template PageNode.
	 * @deprecated Use
	 *             {@link #getPageNode(String, ToadletContext, RenderParameters)}
	 *             instead
	 */
	@Deprecated
	public PageNode getPageNode(String title, boolean renderNavigationLinks, ToadletContext ctx) {
		return getPageNode(title, renderNavigationLinks, true, ctx);
	}

	/**
	 * Generates an FProxy template page with optional navigation bar and status
	 * information suitable for adding content to.
	 *
	 * @param title
	 *            Title of the page.
	 * @param renderNavigationLinks
	 *            Whether to render navigation links.
	 * @param renderStatus
	 *            Whether to render the status display.
	 * @param ctx
	 *            ToadletContext to use to render the page.
	 * @return A template PageNode.
	 * @deprecated Use
	 *             {@link #getPageNode(String, ToadletContext, RenderParameters)}
	 *             instead
	 */
	@Deprecated
	public PageNode getPageNode(String title, boolean renderNavigationLinks, boolean renderStatus, ToadletContext ctx) {
		return getPageNode(title, ctx, new RenderParameters().renderNavigationLinks(renderNavigationLinks).renderStatus(renderStatus).renderModeSwitch(true));
	}

	/**
	 * Generates an FProxy template page suitable for adding content to.
	 *
	 * @param title
	 *            Title of the page.
	 * @param ctx
	 *            ToadletContext to use to render the page.
	 * @param renderParameters
	 *            Parameters for inclusion or omission of certain page elements
	 * @return A template PageNode.
	 */
	public PageNode getPageNode(String title, ToadletContext ctx, RenderParameters renderParameters) {
		boolean fullAccess = ctx == null ? false : ctx.isAllowedFullAccess();
		HTMLNode pageNode = new HTMLNode.HTMLDoctype("html", "-//W3C//DTD XHTML 1.1//EN");
		HTMLNode htmlNode = pageNode.addChild("html", "xml:lang", NodeL10n.getBase().getSelectedLanguage().isoCode);
		HTMLNode headNode = htmlNode.addChild("head");
		headNode.addChild("meta", new String[] { "http-equiv", "content" }, new String[] { "Content-Type", "text/html; charset=utf-8" });
		headNode.addChild("title", title + " - Freenet");
		//To make something only rendered when javascript is on, then add the jsonly class to it
		headNode.addChild("noscript").addChild("style"," .jsonly {display:none;}");
		if(override != null)
			headNode.addChild(getOverrideContent());
		else 
			headNode.addChild("link", new String[] { "rel", "href", "type", "title" }, new String[] { "stylesheet", "/static/themes/" + theme.code + "/theme.css", "text/css", theme.code });
		
		boolean sendAllThemes =  ctx != null && ctx.getContainer().sendAllThemes();
		
		if(sendAllThemes) {
			for (THEME t: THEME.values()) {
				String themeName = t.code;
				headNode.addChild("link", new String[] { "rel", "href", "type", "media", "title" }, new String[] { "alternate stylesheet", "/static/themes/" + themeName + "/theme.css", "text/css", "screen", themeName });
			}
		}
		
		boolean webPushingEnabled = 
			ctx != null && ctx.getContainer().isFProxyJavascriptEnabled() && ctx.getContainer().isFProxyWebPushingEnabled();
		
		// Add the generated javascript, if it and pushing is enabled
		if (webPushingEnabled) headNode.addChild("script", new String[] { "type", "language", "src" }, new String[] {
				"text/javascript", "javascript", "/static/freenetjs/freenetjs.nocache.js" });
		
		Toadlet t;
		if (ctx != null) {
			t = ctx.activeToadlet();
			t = t.showAsToadlet();
		} else
			t = null;
		String activePath = "";
		if(t != null) activePath = t.path();
		HTMLNode bodyNode = htmlNode.addChild("body",
		        new String[] { "class", "id" },
		        new String[] { "fproxy-page", filterCSSIdentifier("page-"+activePath) });
		//Add a hidden input that has the request's id
		if(webPushingEnabled)
			bodyNode.addChild("input",new String[]{"type","name","value","id"},new String[]{"hidden","requestId",ctx.getUniqueId(),"requestId"});
		
		// Add the client-side localization only when pushing is enabled
		if (webPushingEnabled) {
			bodyNode.addChild("script", new String[] { "type", "language" }, new String[] { "text/javascript", "javascript" }).addChild("%", PushingTagReplacerCallback.getClientSideLocalizationScript());
		}
		
		HTMLNode pageDiv = bodyNode.addChild("div", "id", "page");
		HTMLNode topBarDiv = pageDiv.addChild("div", "id", "topbar");

		if (renderParameters.isRenderStatus() && fullAccess) {
			final HTMLNode statusBarDiv = pageDiv.addChild("div", "id", "statusbar-container").addChild("div", "id", "statusbar");

			 if (node != null && node.clientCore != null) {
				 final HTMLNode alerts = node.clientCore.alerts.createSummary(true);
				 if (alerts != null) {
					 statusBarDiv.addChild(alerts).addAttribute("id", "statusbar-alerts");
					 statusBarDiv.addChild("div", "class", "separator", "\u00a0");
				 }
			 }


			statusBarDiv.addChild("div", "id", "statusbar-language").addChild("a", "href", "/config/node#l10n", NodeL10n.getBase().getSelectedLanguage().fullName);

			if (node.clientCore != null && ctx != null && renderParameters.isRenderModeSwitch()) {
				parseMode(ctx);
				boolean isAdvancedMode = ctx.activeToadlet().container.isAdvancedModeEnabled();
				String uri = ctx.getUri().getQuery();
				Map<String, List<String>> parameters = HTTPRequestImpl.parseUriParameters(uri, true);
				List<String> newModeSwitchValues = new ArrayList<String>();
				newModeSwitchValues.add(String.valueOf(isAdvancedMode ? MODE_SIMPLE : MODE_ADVANCED));
				/* overwrite any previously existing parameter value. */
				parameters.put(MODE_SWITCH_PARAMETER, newModeSwitchValues);

				statusBarDiv.addChild("div", "class", "separator", "\u00a0");
				final HTMLNode switchMode = statusBarDiv.addChild("div", "id", "statusbar-switchmode");
				switchMode.addAttribute("class", isAdvancedMode ? "simple" : "advanced");
				switchMode.addChild("a", "href", "?" + HTTPRequestImpl.createQueryString(parameters, false), isAdvancedMode ? NodeL10n.getBase().getString("StatusBar.switchToSimpleMode") : NodeL10n.getBase().getString("StatusBar.switchToAdvancedMode"));
			}

			if (node != null && node.clientCore != null) {
				statusBarDiv.addChild("div", "class", "separator", "\u00a0");
				final HTMLNode secLevels = statusBarDiv.addChild("div", "id", "statusbar-seclevels", NodeL10n.getBase().getString("SecurityLevels.statusBarPrefix"));

				final HTMLNode network = secLevels.addChild("a", "href", "/seclevels/", SecurityLevels.localisedName(node.securityLevels.getNetworkThreatLevel()) + "\u00a0");
				network.addAttribute("title", NodeL10n.getBase().getString("SecurityLevels.networkThreatLevelShort"));
				network.addAttribute("class", node.securityLevels.getNetworkThreatLevel().toString().toLowerCase());

				final HTMLNode physical = secLevels.addChild("a", "href", "/seclevels/", SecurityLevels.localisedName(node.securityLevels.getPhysicalThreatLevel()));
				physical.addAttribute("title", NodeL10n.getBase().getString("SecurityLevels.physicalThreatLevelShort"));
				physical.addAttribute("class", node.securityLevels.getPhysicalThreatLevel().toString().toLowerCase());

				statusBarDiv.addChild("div", "class", "separator", "\u00a0");

				final int connectedPeers = node.peers.countConnectedPeers();
				int darknetTotal = 0;
				for(DarknetPeerNode n : node.peers.getDarknetPeers()) {
					if(n == null) continue;
					if(n.isDisabled()) continue;
					darknetTotal++;
				}
				final int connectedDarknetPeers = node.peers.countConnectedDarknetPeers();
				final int totalPeers = (node.getOpennet() == null) ? (darknetTotal > 0 ? darknetTotal : Integer.MAX_VALUE) : node.getOpennet().getNumberOfConnectedPeersToAimIncludingDarknet();
				final double connectedRatio = ((double)connectedPeers) / (double)totalPeers;
				final String additionalClass;

				// If we use Opennet, we color the bar by the ratio of connected nodes
				if(connectedPeers > connectedDarknetPeers) {
					if (connectedRatio < 0.3D || connectedPeers < 3) {
						additionalClass = "very-few-peers";
					} else if (connectedRatio < 0.5D) {
						additionalClass = "few-peers";
					} else if (connectedRatio < 0.75D) {
						additionalClass = "avg-peers";
					} else {
						additionalClass = "full-peers";
					}
				} else {
					// If we are darknet only, we color by absolute connected peers
					if (connectedDarknetPeers < 3) {
						additionalClass = "very-few-peers";
					} else if (connectedDarknetPeers < 5) {
						additionalClass = "few-peers";
					} else if (connectedDarknetPeers < 10) {
						additionalClass = "avg-peers";
					} else {
						additionalClass = "full-peers";
					}
				}

				HTMLNode progressBar = statusBarDiv.addChild("div", "class", "progressbar");
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-done progressbar-peers " + additionalClass, "width: " +
						Math.min(100,Math.floor(100*connectedRatio)) + "%;" });

				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", NodeL10n.getBase().getString("StatusBar.connectedPeers", new String[]{"X", "Y"},
						new String[]{Integer.toString(node.peers.countConnectedDarknetPeers()), Integer.toString(node.peers.countConnectedOpennetPeers())}) },
						Integer.toString(connectedPeers) + ((totalPeers != Integer.MAX_VALUE) ? " / " + Integer.toString(totalPeers) : ""));
			}
		}

		topBarDiv.addChild("h1", title);
		if (renderParameters.isRenderNavigationLinks()) {
			SubMenu selected = null;
			// Render the full menu.
			HTMLNode navbarDiv = pageDiv.addChild("div", "id", "navbar");
			HTMLNode navbarUl = navbarDiv.addChild("ul", "id", "navlist");
			synchronized (this) {
				for (SubMenu menu : menuList) {
					HTMLNode subnavlist = new HTMLNode("ul");
					boolean isSelected = false;
					boolean nonEmpty = false;
					for (String navigationLink :  fullAccess ? menu.navigationLinkTexts : menu.navigationLinkTextsNonFull) {
						LinkEnabledCallback cb = menu.navigationLinkCallbacks.get(navigationLink);
						if(cb != null && !cb.isEnabled(ctx)) continue;
						nonEmpty = true;
						String navigationTitle = menu.navigationLinkTitles.get(navigationLink);
						String navigationPath = menu.navigationLinks.get(navigationLink);
						HTMLNode sublistItem;
						if(activePath.equals(navigationPath)) {
							sublistItem = subnavlist.addChild("li", "class", "submenuitem-selected");
							isSelected = true;
						} else {
							sublistItem = subnavlist.addChild("li", "class", "submenuitem-not-selected");;
						}
						
						FredPluginL10n l10n = menu.navigationLinkL10n.get(navigationLink);
						if(l10n == null) l10n = menu.plugin;
						if(l10n != null) {
							if(navigationTitle != null) {
								String newNavigationTitle = l10n.getString(navigationTitle);
								if(newNavigationTitle == null) {
									Logger.error(this, "Plugin '"+l10n+"' did return null in getString(key)!");
								} else {
									navigationTitle = newNavigationTitle;
								}
							}
							if(navigationLink != null) {
								String newNavigationLink = l10n.getString(navigationLink);
								if(newNavigationLink == null) {
									Logger.error(this, "Plugin '"+l10n+"' did return null in getString(key)!");
								} else {
									navigationLink = newNavigationLink;
								}
							}
						} else {
							if(navigationTitle != null) navigationTitle = NodeL10n.getBase().getString(navigationTitle);
							if(navigationLink != null) navigationLink = NodeL10n.getBase().getString(navigationLink);
						}
						if(navigationTitle != null)
							sublistItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, navigationTitle }, navigationLink);
						else
							sublistItem.addChild("a", "href", navigationPath, navigationLink);
					}
					if(nonEmpty) {
						HTMLNode listItem;
						if(isSelected) {
							selected = menu;
							subnavlist.addAttribute("class", "subnavlist-selected");
							listItem = new HTMLNode("li", "class", "navlist-selected");
						} else {
							subnavlist.addAttribute("class", "subnavlist");
							listItem = new HTMLNode("li", "class", "navlist-not-selected");
						}
						String menuItemTitle = menu.defaultNavigationLinkTitle;
						String text = menu.navigationLinkText;
						if(menu.plugin == null) {
							//If not from a plugin, add the localization key as id.
							listItem.addAttribute("id", filterCSSIdentifier(menuItemTitle));

							menuItemTitle = NodeL10n.getBase().getString(menuItemTitle);
							text = NodeL10n.getBase().getString(text);
						} else {
							/* If from a plugin, add localization key appended to class
							 * name, separated by a dash, so that plugins with multiple
							 * menus still have distinguishable IDs. Please note that a
							 * plugin could misbehave and not register its menu with proper
							 * localization keys.
							 */
							String id = menu.plugin.getClass().getName()+'-'+text;
							listItem.addAttribute("id", filterCSSIdentifier(id));

							String newTitle = menu.plugin.getString(menuItemTitle);
							if(newTitle == null) {
								Logger.error(this, "Plugin '"+menu.plugin+"' did return null in getString(key)!");
							} else {
								menuItemTitle = newTitle;
							}
							String newText = menu.plugin.getString(text);
							if(newText == null) {
								Logger.error(this, "Plugin '"+menu.plugin+"' did return null in getString(key)!");
							} else {
								text = newText;
							}
						}
						
						listItem.addChild("a", new String[] { "href", "title" }, new String[] { menu.defaultNavigationLink, menuItemTitle }, text);
						listItem.addChild(subnavlist);
						navbarUl.addChild(listItem);
					}
				}
			}
			// Some themes want the selected submenu separately.
			if(selected != null) {
				HTMLNode div = new HTMLNode("div", "id", "selected-subnavbar");
				HTMLNode subnavlist = div.addChild("ul", "id", "selected-subnavbar-list");
				boolean nonEmpty = false;
				for (String navigationLink :  fullAccess ? selected.navigationLinkTexts : selected.navigationLinkTextsNonFull) {
					LinkEnabledCallback cb = selected.navigationLinkCallbacks.get(navigationLink);
					if(cb != null && !cb.isEnabled(ctx)) continue;
					nonEmpty = true;
					String navigationTitle = selected.navigationLinkTitles.get(navigationLink);
					String navigationPath = selected.navigationLinks.get(navigationLink);
					HTMLNode sublistItem;
					if(activePath.equals(navigationPath)) {
						sublistItem = subnavlist.addChild("li", "class", "submenuitem-selected");
					} else {
						sublistItem = subnavlist.addChild("li", "class", "submenuitem-not-selected");
					}
					
					FredPluginL10n l10n = selected.navigationLinkL10n.get(navigationLink);
					if (l10n == null) l10n = selected.plugin;
					if(l10n != null) {
						if(navigationTitle != null) navigationTitle = l10n.getString(navigationTitle);
						if(navigationLink != null) navigationLink = l10n.getString(navigationLink);
					} else {
						if(navigationTitle != null) navigationTitle = NodeL10n.getBase().getString(navigationTitle);
						if(navigationLink != null) navigationLink = NodeL10n.getBase().getString(navigationLink);
					}
					if(navigationTitle != null)
						sublistItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, navigationTitle }, navigationLink);
					else
						sublistItem.addChild("a", "href", navigationPath, navigationLink);
				}
				if(nonEmpty)
					pageDiv.addChild(div);
			}
		}
		HTMLNode contentDiv = pageDiv.addChild("div", "id", "content");
		return new PageNode(pageNode, headNode, contentDiv);
	}

	/**
	 * Filters a given string so that it will be a valid CSS identifier. It replaces all characters that are not
	 * a dash, underscore, or alphanumeric with an underscore. If the first character is a dash and the second
	 * character is not a letter or underscore, replaces the second character with an underscore. This filter is
	 * overly strict as it does not allow non-ASCII characters or escapes. If the given string is below two
	 * characters in length, it appends underscores until it is not.
	 * @param input string to filter
	 * @return a filtered string guaranteed to be a syntactically valid CSS identifier.
	 * @link http://www.w3.org/TR/CSS21/syndata.html#tokenization
	 * @link http://www.w3.org/TR/CSS21/grammar.html#scanner
	 * @link http://stackoverflow.com/questions/448981/
	 */
	public static String filterCSSIdentifier(String input) {
		while (input.length() < 2) input = input.concat("_");

		return input.replaceFirst("^-[^_a-zA-Z]", "-_").replaceAll("[^-_a-zA-Z0-9]", "_");
	}

	public THEME getTheme() {
		return this.theme;
	}

	public InfoboxNode getInfobox(String header) {
		return getInfobox(header, null, false);
	}

	public InfoboxNode getInfobox(HTMLNode header) {
		return getInfobox(header, null, false);
	}

	public InfoboxNode getInfobox(String category, String header) {
		return getInfobox(category, header, null, false);
	}

	public HTMLNode getInfobox(String category, String header, HTMLNode parent) {
		return getInfobox(category, header, parent, null, false);
	}

	public InfoboxNode getInfobox(String category, HTMLNode header) {
		return getInfobox(category, header, null, false);
	}

	public InfoboxNode getInfobox(String header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(new HTMLNode("#", header), title, isUnique);
	}
	
	public InfoboxNode getInfobox(HTMLNode header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(null, header, title, isUnique);
	}

	public InfoboxNode getInfobox(String category, String header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(category, new HTMLNode("#", header), title, isUnique);
	}

	/** Create an infobox, attach it to the given parent, and return the content node. */
	public HTMLNode getInfobox(String category, String header, HTMLNode parent, String title, boolean isUnique) {
		InfoboxNode node = getInfobox(category, header, title, isUnique);
		parent.addChild(node.outer);
		return node.content;
	}

	/**
	 * Returns an infobox with the given style and header.
	 * 
	 * @param category
	 *            The CSS styles, separated by a space (' ')
	 * @param header
	 *            The header HTML node
	 * @return The infobox
	 */
	public InfoboxNode getInfobox(String category, HTMLNode header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();

		StringBuffer classes = new StringBuffer("infobox");
		if(category != null) {
			classes.append(" ");
			classes.append(category);
		}
		if(title != null && !isUnique) {
			classes.append(" ");
			classes.append(title);
		}

		HTMLNode infobox = new HTMLNode("div", "class", classes.toString());

		if(title != null && isUnique) {
			infobox.addAttribute("id", title);
		}

		infobox.addChild("div", "class", "infobox-header").addChild(header);
		return new InfoboxNode(infobox, infobox.addChild("div", "class", "infobox-content"));
	}
	
	private HTMLNode getOverrideContent() {
		return new HTMLNode("link", new String[] { "rel", "href", "type", "media", "title" }, new String[] { "stylesheet", override, "text/css", "screen", "custom" });
	}

	public boolean advancedMode(HTTPRequest req, ToadletContainer container) {
		return parseMode(req, container) >= MODE_ADVANCED;
	}

	/** Call this before getPageNode(), so the menus reflect the advanced mode setting. */
	@Deprecated
	public int parseMode(HTTPRequest req, ToadletContainer container) {
		int mode = container.isAdvancedModeEnabled() ? MODE_ADVANCED : MODE_SIMPLE;

		if(req.isParameterSet(MODE_SWITCH_PARAMETER)) {
			mode = req.getIntParam(MODE_SWITCH_PARAMETER, mode);
			if(mode == MODE_ADVANCED)
				container.setAdvancedMode(true);
			else
				container.setAdvancedMode(false);
		}
		
		return mode;
	}
	
	private void parseMode(ToadletContext ctx) {
		HTTPRequest req = new HTTPRequestImpl(ctx.getUri(), "GET");
		if(req.isParameterSet(MODE_SWITCH_PARAMETER))
			ctx.getContainer().setAdvancedMode(req.getIntParam(MODE_SWITCH_PARAMETER) == MODE_ADVANCED);
	}
	
	/**
	 * Bundles parameters that are used to create the page node. The default for
	 * the render parameters is to include all optional render tasks. Individual
	 * tasks may be enabled or disabled by calling the appropriate methods which
	 * returns a new {@link RenderParameters} object as {@link RenderParameters}
	 * are immutable.
	 *
	 * @see PageMaker#getPageNode(String, ToadletContext, RenderParameters)
	 * @author <a href="mailto:bombe@pterodactylus.net">David ?Bombe? Roden</a>
	 */
	public static class RenderParameters {

		/** Whether to include navigation links in the page. */
		private final boolean renderNavigationLinks;

		/** Whether to include the status bar in the page. */
		private final boolean renderStatus;

		/** Whether to include the mode switch in the page. */
		private final boolean renderModeSwitch;

		/**
		 * Creates default render parameters that include all elements.
		 */
		public RenderParameters() {
			this(true, true, true);
		}

		/**
		 * Creates render parameters.
		 *
		 * @param renderNavigationLinks
		 *            {@code true} to include navigation links in the page
		 * @param renderStatus
		 *            {@code true} to include the status bar in the page
		 * @param renderModeSwitch
		 *            {@code true} to include the mode switch in the status bar
		 */
		private RenderParameters(boolean renderNavigationLinks, boolean renderStatus, boolean renderModeSwitch) {
			this.renderNavigationLinks = renderNavigationLinks;
			this.renderStatus = renderStatus;
			this.renderModeSwitch = renderModeSwitch;
		}

		//
		// ACCESSORS
		//

		/**
		 * Returns whether the navigation links should be included in the page.
		 *
		 * @return {@code true} if the navigation links should be included in
		 *         the page, {@code false} otherwise
		 */
		public boolean isRenderNavigationLinks() {
			return renderNavigationLinks;
		}

		/**
		 * Returns a new {@link RenderParameters} object that renders the
		 * navigation links according to the given parameter.
		 *
		 * @param renderNavigationLinks
		 *            {@code true} to render the navigation links, {@code false}
		 *            otherwise
		 * @return A new {@link RenderParameters} object
		 */
		public RenderParameters renderNavigationLinks(boolean renderNavigationLinks) {
			return new RenderParameters(renderNavigationLinks, renderStatus, renderModeSwitch);
		}

		/**
		 * Returns whether the status bar should be included in the page.
		 *
		 * @return {@code true} if the status bar should be included in the
		 *         page, {@code false} otherwise
		 */
		public boolean isRenderStatus() {
			return renderStatus;
		}

		/**
		 * Returns a new {@link RenderParameters} object that renders the status
		 * bar according to the given parameter.
		 *
		 * @param renderStatus
		 *            {@code true} to render the status bar, {@code false}
		 *            otherwise
		 * @return A new {@link RenderParameters} object
		 */
		public RenderParameters renderStatus(boolean renderStatus) {
			return new RenderParameters(renderNavigationLinks, renderStatus, renderModeSwitch);
		}

		/**
		 * Returns whether the mode switch should be included in the page.
		 *
		 * @return {@code true} if the mode switch should be included in the
		 *         page, {@code false} otherwise
		 */
		public boolean isRenderModeSwitch() {
			return renderModeSwitch;
		}

		/**
		 * Returns a new {@link RenderParameters} object that renders the mode
		 * switch according to the given parameter.
		 *
		 * @param renderModeSwitch
		 *            {@code true} to render the mode switch, {@code false}
		 *            otherwise
		 * @return A new {@link RenderParameters} object
		 */
		public RenderParameters renderModeSwitch(boolean renderModeSwitch) {
			return new RenderParameters(renderNavigationLinks, renderStatus, renderModeSwitch);
		}

	}

}
