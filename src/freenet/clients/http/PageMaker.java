package freenet.clients.http;

import java.util.Vector;
import java.util.Collection;
import java.util.Iterator;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public class PageMaker {
	private final String defaulttheme = new String("aqua");
	public String theme;
	
	PageMaker(String t) {
		if (t == null || !this.getThemes().contains(t)) {
			this.theme = this.defaulttheme;
		} else {
			this.theme = t;
		}
	}
	
	public void makeTopHead(StringBuffer buf) {
		buf.append("<!DOCTYPE\n"
				+ "	html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\">\n"
				+ "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">\n"
				+ "<head>\n"
				+ "<meta http-equiv=\"Content-Type\" content=\"text/html;\" />\n"
				+"<link rel=\"stylesheet\" href=\"/static/themes/"+this.theme+"/theme.css\" type=\"text/css\" />\n");
	}
	
	public void makeBottomHead(StringBuffer buf, String title) {
		buf.append("<title>"+title+" - Freenet</title>\n"
				+ "</head>\n"
				+ "<body>\n"
				+ "<div id=\"page\">\n"
				+ "<div id=\"topbar\">\n"
				+ "<h1>"+title+"</h1>\n"
				+ "</div>\n");
		this.makeNavBar(buf);
		buf.append("<div id=\"content\">\n");
	}
	
	public void makeHead(StringBuffer buf, String title) {
		makeTopHead(buf);
		makeBottomHead(buf, title);
	}
	
	public void makeTail(StringBuffer buf) {
		buf.append("<br style=\"clear: all;\"/>\n"
				+ "</div>\n"
				+ "<div id=\"themeselect\">\n");
		this.makeThemeSelect(buf);
		buf.append("</div>\n"
				+"</div>\n"
				+"</body>\n"
				+ "</html>\n");
	}
	
	public Collection getThemes() {
		// Sadly I can't find a way to enumerate the contents of the themes directory
		// (since it may or may not be in a jar file)
		Vector themes = new Vector();
		
		themes.add("aqua");
		themes.add("clean");
		
		return themes;
	}
	
	private void makeNavBar(StringBuffer buf) {
		buf.append("<div id=\"navbar\">\n"
				+ "<ul id=\"navlist\">\n"
				+ "<li><a href=\"/\" title=\"Homepage\">Home</a></li>\n"
				+ "<li><a href=\"/plugins/\" title=\"Configure Plugins\">Plugins</a></li>\n"
				+ "<li><a href=\"/config/\" title=\"Configure your node\">Configuration</a></li>\n"
				+ "</ul>\n"
				+ "</div>\n");
	}
	
	private void makeThemeSelect(StringBuffer buf) {
		Collection themes = this.getThemes();
		
		Iterator i = themes.iterator();
		
		buf.append("Theme: <ul>\n");
		String liclass = new String("");
		while (i.hasNext()) {
			String t = (String) i.next();
			
			if (t.equals(this.theme)) {
				liclass = "currentthemeitem";
			} else {
				liclass = "themeitem";
			}
			buf.append("<li class=\""+liclass+"\">\n"
					+"<a href=\"javascript:document.cookie = 'theme="+t+"; expires=Mon, 04 Mar 2030 00:00:00 GMT; path=/';window.location.reload(true)\" title=\""+t+" theme\">\n"
					+ t+"</a>\n"
					+ "</li>\n");
		}
		buf.append("</ul>\n");
	}
}
