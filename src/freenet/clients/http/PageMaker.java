package freenet.clients.http;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public class PageMaker {
	// TODO: make this...err... not a constant.
	private final String theme = new String("default");

	public void makeTopHead(StringBuffer buf) {
		buf.append("<!DOCTYPE\n"
				+ "	html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\"\n"
				+ "	\"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n"
				+ "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">\n"
				+ "<head>\n"
				+ "<meta http-equiv=\"Content-Type\" content=\"text/html;\" />\n"
				+ "<link rel=\"stylesheet\" href=\"/static/themes/"+theme+"/theme.css\" type=\"text/css\" />\n");
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
		buf.append("<br style=\"clear: all;\"/>"
				+ "</div>\n"
				+"</div>\n"
				+"</body>\n"
				+ "</html>\n");
	}
	
	private void makeNavBar(StringBuffer buf) {
		buf.append("<div id=\"navbar\">\n"
				+ "<ul id=\"navlist\">\n"
				+ "<li><a href=\"/\" title=\"Homepage\">Home</a></li>\n"
				+ "<li><a href=\"/plugins/\" title=\"Configure Plugins\">Plugins</a></li>\n"
				+ "</ul>\n"
				+ "</div>\n");
	}
}
