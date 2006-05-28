package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import freenet.support.Logger;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public class PageMaker {
	
	private static final String DEFAULT_THEME = "aqua";
	public String theme;
	
	/** Cache for themes read from the JAR file. */
	public List jarThemesCache = null;
	
	PageMaker(String t) {
		if (t == null) {
			this.theme = DEFAULT_THEME;
		} else {
			URL themeurl = getClass().getResource("staticfiles/themes/"+t+"/theme.css");
			if (themeurl == null)
				this.theme = DEFAULT_THEME;
			else
				this.theme = t;
		}
	}
	
	public void makeBackLink(StringBuffer buf, ToadletContext ctx){
		// My browser sends it with one 'r'
		String ref = (String)ctx.getHeaders().get("referer");
		if(ref!=null) 
			buf.append("<br><a href=\""+ref+"\" title=\"Back\" Back</a>\n");
		else
			buf.append("<br><a href=\"javascript:back()\" title=\"Back\">Back</a>\n");
		
	}
	
	public void makeTopHead(StringBuffer buf) {
		buf.append("<!DOCTYPE\n"
				+ "	html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\">\n"
				+ "<html xml:lang=\"en\">\n"
				+ "<head>\n"
				+ "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n"
				+ "<link rel=\"stylesheet\" href=\"/static/themes/"+this.theme+"/theme.css\" type=\"text/css\" />\n");
		List themes = getThemes();
		for(int i=0; i<themes.size() ; i++){
			buf.append("<link rel=\"alternate stylesheet\" type=\"text/css\" href=\"/static/themes/"+themes.get(i)+"/theme.css\" media=\"screen\" title=\""+themes.get(i)+"\" />\n");
		}
	}
	
	public void makeBottomHead(StringBuffer buf, String title, boolean navbars) {
		buf.append("<title>"+title+" - Freenet</title>\n"
				+ "</head>\n"
				+ "<body>\n"
				+ "<div id=\"page\">\n"
				+ "<div id=\"topbar\">\n"
				+ "<h1>"+title+"</h1>\n"
				+ "</div>\n");
		if (navbars) this.makeNavBar(buf);
		buf.append("<div id=\"content\">\n");
	}
	
	public void makeBottomHead(StringBuffer buf, String title) {
		makeBottomHead(buf, title, true);
	}
	
	public void makeHead(StringBuffer buf, String title) {
		makeTopHead(buf);
		makeBottomHead(buf, title);
	}
	
	public void makeHead(StringBuffer buf, String title, boolean navbars) {
		makeTopHead(buf);
		makeBottomHead(buf, title, navbars);
	}
	
	public void makeTail(StringBuffer buf) {
		buf.append("<br style=\"clear: all;\"/>\n"
				+ "</div>\n"
				+"</div>\n"
				+"</body>\n"
				+ "</html>\n");
	}
	
	/**
	 * Returns a {@link Collection} containing the names of all available
	 * themes. If freenet was started from a JAR file the list is cached
	 * (because the JAR file only changes between invocations), otherwise the
	 * filesystem is read on every page access.
	 * 
	 * @return A {@link Collection} containing the names of all available themes
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
				for (int themeIndex = 0; themeDirectories != null && themeIndex < themeDirectories.length; themeIndex++) {
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
			Logger.error(this, "error creating list of themes", npe);
		} finally {
			if (!themes.contains("aqua")) {
				themes.add("aqua");
			}
			if (!themes.contains("clean")) {
				themes.add("clean");
			}
		}
		return themes;
	}
	
	private void makeNavBar(StringBuffer buf) {
		buf.append("<div id=\"navbar\">\n"
				+ "<ul id=\"navlist\">\n"
				+ "<li><a href=\"/\" title=\"Homepage\">Home</a></li>\n"
				+ "<li><a href=\"/plugins/\" title=\"Configure Plugins\">Plugins</a></li>\n"
				+ "<li><a href=\"/config/\" title=\"Configure your node\">Configuration</a></li>\n"
				+ "<li><a href=\"/darknet/\" title=\"Manage darknet connections\">Darknet</a></li>\n"
				+ "<li><a href=\"/queue/\" title=\"Manage queued requests\">Queue</a></li>\n"
				+ "</ul>\n"
				+ "</div>\n");
	}
}
