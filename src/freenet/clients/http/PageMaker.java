package freenet.clients.http;

import java.util.Collection;
import java.util.Vector;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public class PageMaker {
	
	private static final String DEFAULT_THEME = "aqua";
	public String theme;
	
	PageMaker(String t) {
		if (t == null || !this.getThemes().contains(t)) {
			this.theme = DEFAULT_THEME;
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
		for(int i=0; i<getThemes().size() ; i++){
			buf.append("<link rel=\"alternate stylesheet\" type=\"text/css\" href=\"/static/themes/"+getThemes().toArray()[i].toString()+"/theme.css\" media=\"screen\" title=\""+getThemes().toArray()[i].toString()+"\" />\n");
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
				+ "<li><a href=\"/darknet/\" title=\"Manage darknet connections\">Darknet</a></li>\n"
				+ "</ul>\n"
				+ "</div>\n");
	}
}
