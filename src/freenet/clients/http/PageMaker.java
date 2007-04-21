package freenet.clients.http;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
import freenet.support.HTMLNode;
import freenet.support.Logger;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public class PageMaker {
	
	private static final String DEFAULT_THEME = "clean";
	private String theme;
	private File override;
	private final List navigationLinkTexts = new ArrayList();
	private final List navigationLinkTextsNonFull = new ArrayList();
	private final Map navigationLinkTitles = new HashMap();
	private final Map navigationLinks = new HashMap();
	private final Map contentNodes = new HashMap();
	
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
	
	public void addNavigationLink(String path, String name, String title, boolean fullOnly) {
		navigationLinkTexts.add(name);
		if(!fullOnly)
			navigationLinkTextsNonFull.add(name);
		navigationLinkTitles.put(name, title);
		navigationLinks.put(name, path);
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
		HTMLNode htmlNode = pageNode.addChild("html", "xml:lang", L10n.getSelectedLanguage());
		HTMLNode headNode = htmlNode.addChild("head");
		headNode.addChild("title", title + " - Freenet");
		headNode.addChild("meta", new String[] { "http-equiv", "content" }, new String[] { "Content-Type", "text/html; charset=utf-8" });
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
				String navigationTitle = (String) navigationLinkTitles.get(navigationLink);
				String navigationPath = (String) navigationLinks.get(navigationLink);
				HTMLNode listItem = navbarUl.addChild("li");
				listItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, navigationTitle }, navigationLink);
			}
		}
		HTMLNode contentDiv = pageDiv.addChild("div", "id", "content");
		contentNodes.put(pageNode, contentDiv);
		return pageNode;
	}
	
	/**
	 * Returns the content node that belongs to the specified page node.
	 * <p>
	 * <strong>Warning:</strong> this method can only be called once!
	 * 
	 * @param pageNode
	 *            The page node to get the content node for
	 * @return The content node for the specified page node
	 */
	public HTMLNode getContentNode(HTMLNode pageNode) {
		return (HTMLNode) contentNodes.remove(pageNode);
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
	
	public HTMLNode getInfobox(String category, HTMLNode header) {
		if (header == null) throw new NullPointerException();
		HTMLNode infobox = new HTMLNode("div", "class", "infobox" + ((category == null) ? "" : (' ' + category)));
		if (header != null) {
			infobox.addChild("div", "class", "infobox-header").addChild(header);
		}
		contentNodes.put(infobox, infobox.addChild("div", "class", "infobox-content"));
		return infobox;
	}
	
	/**
	 * Returns an {@link ArrayList} containing the names of all available
	 * themes. If freenet was started from a JAR file the list is cached
	 * (because the JAR file only changes between invocations), otherwise the
	 * filesystem is read on every page access.
	 * 
	 * @return An {@link ArrayList} containing the names of all available themes
	 */
	public List getThemes() {
		if (jarThemesCache != null) {
			return jarThemesCache;
		}
		List themes = new ArrayList();
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
		FileInputStream fis = null;
		BufferedInputStream bis = null;
		InputStreamReader isr = null;
		
		try {
			fis = new FileInputStream(override);
			bis = new BufferedInputStream(fis);
			isr = new InputStreamReader(bis);
			StringBuffer sb = new StringBuffer();
			
			char[] buf = new char[4096];
			
			while(isr.ready()) {
				isr.read(buf);
				sb.append(buf);
			}
			
			result.addChild("#", sb.toString());
			
		} catch (IOException e) {
			Logger.error(this, "Got an IOE: " + e.getMessage(), e);
		} finally {
			try {
				if(isr != null) isr.close();
				if(bis != null) bis.close();
				if(fis != null) fis.close();
			} catch (IOException e) {}
		}
		return result;
	}
}
