package freenet.clients.http;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;

import freenet.client.HighLevelSimpleClient;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.node.Version;
import freenet.pluginmanager.HTTPRequest;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;

public class WelcomeToadlet extends Toadlet {
	private static final String[] DEFAULT_TESTNET_BOOKMARKS = {
		"USK@60I8H8HinpgZSOuTSD66AVlIFAy-xsppFr0YCzCar7c,NzdivUGCGOdlgngOGRbbKDNfSCnjI0FXjHLzJM4xkJ4,AQABAAE/index/4/=INDEX.7-freesite"
	};
	private static final String[] DEFAULT_DARKNET_BOOKMARKS = {
		"USK@PFeLTa1si2Ml5sDeUy7eDhPso6TPdmw-2gWfQ4Jg02w,3ocfrqgUMVWA2PeorZx40TW0c-FiIOL-TWKQHoDbVdE,AQABAAE/Index/-1/=Darknet Index"
	};
	Node node;
	SubConfig config;
	BookmarkManager bookmarks;
	
	WelcomeToadlet(HighLevelSimpleClient client, Node n, SubConfig sc) {
		super(client);
		this.node = n;
		this.config = sc;
		this.bookmarks = new BookmarkManager(n);
		
		sc.register("bookmarks", n.isTestnetEnabled() ? DEFAULT_TESTNET_BOOKMARKS : DEFAULT_DARKNET_BOOKMARKS, 0, false, "List of bookmarks", "A list of bookmarked freesites", this.bookmarks.makeCB());
		
		String[] initialbookmarks = sc.getStringArr("bookmarks");
		for (int i = 0; i < initialbookmarks.length; i++) {
			try {
				bookmarks.addBookmark(new Bookmark(initialbookmarks[i]));
			} catch (MalformedURLException mue) {
				// just ignore that one
			}
		}
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, config servlet limited to 1MB");
			return;
		}
		byte[] d = BucketTools.toByteArray(data);
		String s = new String(d, "us-ascii");
		HTTPRequest request;
		try {
			request = new HTTPRequest("/", s);
		} catch (URISyntaxException e) {
			Logger.error(this, "Impossible: "+e, e);
			return;
		}
		
		StringBuffer buf = new StringBuffer();
		
		if (request.getParam("shutdownconfirm").length() > 0) {
			// false for no navigation bars, because that would be very silly
			ctx.getPageMaker().makeHead(buf, "Node Shut down", false);
			buf.append("<div class=\"infobox\">\n");
			buf.append("The Freenet node has been successfully shut down\n");
			buf.append("<br />\n");
			buf.append("Thank you for using Freenet\n");
			buf.append("</div>\n");
			ctx.getPageMaker().makeTail(buf);
				
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
			this.node.exit();
		} else if (request.getParam("exit").equalsIgnoreCase("true")) {
			ctx.getPageMaker().makeHead(buf, "Node Shutdown");
			buf.append("<form action=\"/\" method=\"post\">\n");
			buf.append("<div class=\"infobox\">\n");
			buf.append("Are you sure you wish to shut down your Freenet node?<br />\n");
			buf.append("<div class=\"cancel\">\n");
			buf.append("<input type=\"submit\" name=\"cancel\" value=\"Cancel\" />\n");
			buf.append("</div>\n");
			buf.append("<div class=\"confirm\">\n");
			buf.append("<input type=\"submit\" name=\"shutdownconfirm\" value=\"Shut Down\" />\n");
			buf.append("</div>\n");
			buf.append("<br style=\"clear: all;\">\n");
			buf.append("</div>\n");
			buf.append("</form>\n");
			ctx.getPageMaker().makeTail(buf);
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
		} else if (request.getParam("newbookmark").length() > 0) {
			try {
				bookmarks.addBookmark(new Bookmark(request.getParam("newbookmark"), request.getParam("name")));
			} catch (MalformedURLException mue) {
				this.sendErrorPage(ctx, 200, "Invalid freenet key", "Could not add the given item as a bookmark since it was not possible to interpret it as a freenet key. Please <a href=\"/\">Try Again</a>");
				return;
			}
			
			try {
				this.handleGet(new URI("/welcome/?managebookmarks"), ctx);
			} catch (URISyntaxException ex) {
				
			}
		} else if (request.isParameterSet("managebookmarks")) {
			Enumeration e = bookmarks.getBookmarks();
			while (e.hasMoreElements()) {
				Bookmark b = (Bookmark)e.nextElement();
			
				if (request.isParameterSet("delete_"+b.hashCode())) {
					bookmarks.removeBookmark(b);
				}
			}
			try {
				this.handleGet(new URI("/welcome/?managebookmarks"), ctx);
			} catch (URISyntaxException ex) {
				
			}
		} else {
			this.handleGet(uri, ctx);
		}
	}
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		
		HTTPRequest request = new HTTPRequest(uri);
		if (request.getParam("newbookmark").length() > 0) {
			ctx.getPageMaker().makeHead(buf, "Add a Bookmark");
			
			buf.append("<form action=\".\" method=\"post\">\n");
			buf.append("<div>\n");
			buf.append("Please confirm that you wish to add the key:<br />\n");
			buf.append("<i>"+request.getParam("newbookmark")+"</i><br />");
			buf.append("To your bookmarks, and enter the description that you would prefer:<br />\n");
			buf.append("Description:\n");
			buf.append("<input type=\"text\" name=\"name\" value=\""+HTMLEncoder.encode(request.getParam("desc"))+"\" style=\"width: 100%; \" />\n");
			buf.append("<input type=\"hidden\" name=\"newbookmark\" value=\""+HTMLEncoder.encode(request.getParam("newbookmark"))+"\" />\n");
			buf.append("<input type=\"submit\" value=\"Add Bookmark\" />\n");
			buf.append("</div>\n");
			buf.append("</form>\n");
			
			ctx.getPageMaker().makeTail(buf);
		
			this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
			return;
		} else if (request.isParameterSet("managebookmarks")) {
			ctx.getPageMaker().makeHead(buf, "Bookmark Manager");
			
			// existing bookmarks
			buf.append("<form action=\".\" method=\"post\">\n");
			buf.append("<div class=\"infobox\">\n");
			buf.append("<h2>My Bookmarks</h2>\n");
			Enumeration e = bookmarks.getBookmarks();
			if (!e.hasMoreElements()) {
				buf.append("<i>You currently have no bookmarks defined</i>");
			} else {
				buf.append("<ul id=\"bookmarks\">\n");
				while (e.hasMoreElements()) {
					Bookmark b = (Bookmark)e.nextElement();
				
					buf.append("<li><a href=\"/"+HTMLEncoder.encode(b.getKey())+"\">");
					buf.append(HTMLEncoder.encode(b.getDesc()));
					buf.append("</a>\n");
					buf.append("<input type=\"submit\" name=\"delete_"+b.hashCode()+"\" value=\"Delete\" style=\"float: right; \" />\n");
					buf.append("</li>\n");
				}
				buf.append("</ul>\n");
			}
			buf.append("<input type=\"hidden\" name=\"managebookmarks\" value=\"yes\" />\n");
			buf.append("</div>\n");
			buf.append("</form>\n");
			
			// new bookmark
			buf.append("<form action=\".\" method=\"post\">\n");
			buf.append("<div class=\"infobox\">\n");
			buf.append("<h2>New Bookmark</h2>\n");
			buf.append("Key: \n");
			buf.append("<input type=\"text\" name=\"newbookmark\" style=\"width: 100%; \"/>\n");
			buf.append("Description: \n");
			buf.append("<input type=\"text\" name=\"name\" style=\"width: 100%; \"/>\n");
			buf.append("<input type=\"submit\" value=\"Add Bookmark\"/>\n");
			buf.append("</div>\n");
			buf.append("</form>\n");
			
			ctx.getPageMaker().makeTail(buf);
		
			this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
			return;
		}
		
		
		ctx.getPageMaker().makeHead(buf, "Freenet FProxy Homepage");
		if(node.isTestnetEnabled())
			buf.append("<div style=\"color: red; font-size: 200%; \">WARNING: TESTNET MODE ENABLED</div>");

		// Alerts
		
		node.alerts.toHtml(buf);
		
		// Fetch-a-key box
		buf.append("<br style=\"clear: all; \" />\n");
		buf.append("<form action=\"/\" method=\"get\">\n");
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>Fetch a Key</h2>\n");
		buf.append("Key: <input type=\"text\" size=\"80\" name=\"key\"/>\n");
		buf.append("<input type=\"submit\" value=\"Fetch\" />\n");
		buf.append("</div>\n");
		buf.append("</form>\n");
		
		// Bookmarks
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>My Bookmarks</h2>");
		
		Enumeration e = bookmarks.getBookmarks();
		if (!e.hasMoreElements()) {
			buf.append("<i>You currently have no bookmarks defined</i>");
		} else {
			buf.append("<ul id=\"bookmarks\">\n");
			while (e.hasMoreElements()) {
				Bookmark b = (Bookmark)e.nextElement();
				
				buf.append("<li><a href=\"/"+HTMLEncoder.encode(b.getKey())+"\">");
				buf.append(HTMLEncoder.encode(b.getDesc()));
				buf.append("</a></li>\n");
			}
			buf.append("</ul>\n");
		}
		buf.append("<div id=\"bookmarkedit\">\n");
		buf.append("<a href=\"?managebookmarks\" class=\"interfacelink\">Edit My Bookmarks</a>\n");
		buf.append("</div>\n");
		buf.append("</div>\n");
		
		// Version info
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>Version</h2>");
		buf.append("Freenet version "+Version.nodeVersion+" build #"+Version.buildNumber());
		if(Version.buildNumber() < Version.highestSeenBuild) {
			buf.append("<br />");
			buf.append("<b>A newer version is available! (Build #"+Version.highestSeenBuild+")</b>");
		}
		buf.append("</div>\n");
		
		// Quit Form
		buf.append("<form method=\"post\" action=\".\">\n");
		buf.append("<div class=\"exit\">\n");
		buf.append("<input type=\"hidden\" name=\"exit\" value=\"true\" /><input type=\"submit\" value=\"Shut down the node\" />\n");
		buf.append("</div>\n");
		buf.append("</form>\n");
		
		// Activity
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>Current Activity</h2>\n");
		buf.append("<ul id=\"activity\">\n"
				+ "<li>Inserts: "+this.node.getNumInserts()+"</li>\n"
				+ "<li>Requests: "+this.node.getNumRequests()+"</li>\n"
				+ "<li>Transferring Requests: "+this.node.getNumTransferringRequests()+"</li>\n"
				+ "</ul>\n");
		buf.append("</div>\n");
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}
}