package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public final class PageMaker {
	
	public static final String DEFAULT_THEME = "clean";
	public static final int MODE_SIMPLE = 1;
	public static final int MODE_ADVANCED = 2;
	private String theme;
	private File override;
	private final List navigationLinkTexts = new ArrayList();
	private final List navigationLinkTextsNonFull = new ArrayList();
	private final Map navigationLinkTitles = new HashMap();
	private final Map navigationLinks = new HashMap();
	private final Map contentNodes = new HashMap();
	private final Map/*<HTMLNode, HTMLNode>*/ headNodes = new HashMap();
	private final Map /* <String, LinkEnabledCallback> */ navigationLinkCallbacks = new HashMap();
	
	/** Cache for themes read from the JAR file. */
	private List jarThemesCache = null;
	
	public PageMaker(String t) {
		setTheme(t);
	}
	
	void setOverride(File f) {
		this.override = f;
	}
	
	void setTheme(String theme) {
		if (theme == null) {
			this.theme = DEFAULT_THEME;
		} else {
			URL themeurl = getClass().getResource("staticfiles/themes/" + theme + "/theme.css");
			if (themeurl == null)
				this.theme = DEFAULT_THEME;
			else
				this.theme = theme;
		}
	}
        	
	public void addNavigationLink(String path, String name, String title, boolean fullOnly, LinkEnabledCallback cb) {
		navigationLinkTexts.add(name);
		if(!fullOnly)
			navigationLinkTextsNonFull.add(name);
		navigationLinkTitles.put(name, title);
		navigationLinks.put(name, path);
		if(cb != null)
			navigationLinkCallbacks.put(name, cb);
	}
	
	public void removeNavigationLink(String name) {
		navigationLinkTexts.remove(name);
		navigationLinkTextsNonFull.remove(name);
		navigationLinkTitles.remove(name);
		navigationLinks.remove(name);
	}
	
	public HTMLNode createBackLink(ToadletContext toadletContext, String name) {
		String referer = (String) toadletContext.getHeaders().get("referer");
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
			headNode.addChild("link", new String[] { "rel", "href", "type", "title" }, new String[] { "stylesheet", "/static/themes/" + theme + "/theme.css", "text/css", theme });
		else
			headNode.addChild(getOverrideContent());
		List themes = getThemes();
		for (Iterator themesIterator = themes.iterator(); themesIterator.hasNext();) {
			String themeName = (String) themesIterator.next();
			headNode.addChild("link", new String[] { "rel", "href", "type", "media", "title" }, new String[] { "alternate stylesheet", "/static/themes/" + themeName + "/theme.css", "text/css", "screen", themeName });
		}
		
		HTMLNode bodyNode = htmlNode.addChild("body");
		HTMLNode pageDiv = bodyNode.addChild("div", "id", "page");
		HTMLNode topBarDiv = pageDiv.addChild("div", "id", "topbar");
		topBarDiv.addChild("h1", title);
		if (renderNavigationLinks) {
			HTMLNode navbarDiv = pageDiv.addChild("div", "id", "navbar");
			HTMLNode navbarUl = navbarDiv.addChild("ul", "id", "navlist");
			for (Iterator navigationLinkIterator = fullAccess ? navigationLinkTexts.iterator() : navigationLinkTextsNonFull.iterator(); navigationLinkIterator.hasNext();) {
				String navigationLink = (String) navigationLinkIterator.next();
				LinkEnabledCallback cb = (LinkEnabledCallback) navigationLinkCallbacks.get(navigationLink);
				if(cb != null && !cb.isEnabled()) continue;
				String navigationTitle = (String) navigationLinkTitles.get(navigationLink);
				String navigationPath = (String) navigationLinks.get(navigationLink);
				HTMLNode listItem = navbarUl.addChild("li");
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
		return (HTMLNode) headNodes.remove(pageNode);
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
		return (HTMLNode) contentNodes.remove(node);
	}
	
	public HTMLNode getInfobox(String header) {
		return getInfobox((header != null) ? new HTMLNode("#", header) : (HTMLNode) null);
	}
	
	public HTMLNode getInfobox(HTMLNode header) {
		return getInfobox(null, header);
	}

	public HTMLNode getInfobox(String category, String header) {
		return getInfobox(category, (header != null) ? new HTMLNode("#", header) : (HTMLNode) null);
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
	
	/**
	 * Returns an {@link ArrayList} containing the names of all available
	 * themes. If Freenet was started from a JAR file the list is cached
	 * (because the JAR file only changes between invocations), otherwise the
	 * filesystem is read on every page access.
	 * 
	 * @return An {@link ArrayList} containing the names of all available themes
	 */
	public List<String> getThemes() {
		if (jarThemesCache != null) {
			return jarThemesCache;
		}
		List<String> themes = new ArrayList<String>();
		try {
			URL url = getClass().getResource("staticfiles/themes/");
			URLConnection urlConnection = url.openConnection();
			if (url.getProtocol().equals("file")) {
				File themesDirectory = new File(URLDecoder.decode(url.getPath(), "ISO-8859-1").replaceAll("\\|", ":"));
				File[] themeDirectories = themesDirectory.listFiles();
				for (int themeIndex = 0; (themeDirectories != null) && (themeIndex < themeDirectories.length); themeIndex++) {
					File themeDirectory = themeDirectories[themeIndex];
					if (themeDirectory.isDirectory() && !themeDirectory.getName().startsWith(".")) {
						themes.add(themeDirectory.getName());
					}
				}	
			} else if (urlConnection instanceof JarURLConnection) {
				JarURLConnection jarUrlConnection = (JarURLConnection) urlConnection;
				JarFile jarFile = jarUrlConnection.getJarFile();
				Enumeration entries = jarFile.entries();
				while (entries.hasMoreElements()) {
					JarEntry entry = (JarEntry) entries.nextElement();
					String name = entry.getName();
					if (name.startsWith("freenet/clients/http/staticfiles/themes/")) {
						name = name.substring("freenet/clients/http/staticfiles/themes/".length());
						if (name.indexOf('/') != -1) {
							String themeName = name.substring(0, name.indexOf('/'));
							if (!themes.contains(themeName)) {
								themes.add(themeName);
							}
						}
					}
				}
				jarThemesCache = themes;
			}
		} catch (IOException ioe1) {
			Logger.error(this, "error creating list of themes", ioe1);
		} catch (NullPointerException npe) {
			Logger.error(this, "error creating list of themes (if you're using the gnu-classpath, it's \"normal\")", npe);
			themes.add("clean");
		} finally {
			if (!themes.contains("clean")) {
				themes.add("clean");
			}
		}
		return themes;
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
		// Mode can be changed by a link, not just by the default
		
		int mode = core.isAdvancedModeEnabled() ? MODE_ADVANCED : MODE_SIMPLE;
		
		if(req.isParameterSet("mode")) {
			mode = req.getIntParam("mode", mode);
		}
		
		// FIXME style this properly
		HTMLNode table = contentNode.addChild("table", "border", "1");
		HTMLNode row = table.addChild("tr");
		HTMLNode cell = row.addChild("td");
		
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
