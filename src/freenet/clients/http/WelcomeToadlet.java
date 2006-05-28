package freenet.clients.http;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;

import freenet.client.ClientMetadata;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.Version;
import freenet.node.useralerts.UserAlert;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;

public class WelcomeToadlet extends Toadlet {
	private final static int MODE_ADD = 1;
	private final static int MODE_EDIT = 2;
	Node node;
	SubConfig config;
	BookmarkManager bookmarks;
	
	WelcomeToadlet(HighLevelSimpleClient client, Node n, SubConfig sc) {
		super(client);
		this.node = n;
		this.config = sc;
		this.bookmarks = node.bookmarkManager;
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Data exceeds 1MB limit");
			return;
		}
		
		HTTPRequest request = new HTTPRequest(uri,data,ctx);
		if(request==null) return;
		
		StringBuffer buf = new StringBuffer();
		
		if (request.getParam("shutdownconfirm").length() > 0) {
			// false for no navigation bars, because that would be very silly
			ctx.getPageMaker().makeHead(buf, "Node Shutdown", false);
			buf.append("<div class=\"infobox infobox-information\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("The Freenet node has been successfully shut down\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("Thank you for using Freenet\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			ctx.getPageMaker().makeTail(buf);
				
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
			this.node.exit();
			return;
		}else if(request.getParam("restartconfirm").length() > 0){
			// false for no navigation bars, because that would be very silly
			ctx.getPageMaker().makeHead(buf, "Node Restart", false);
			buf.append("<div class=\"infobox infobox-information\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("The Freenet node is beeing restarted\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("The restart process might take up to 3 minutes. <br>");
			buf.append("Thank you for using Freenet\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			ctx.getPageMaker().makeTail(buf);
			
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
			Logger.normal(this, "Node is restarting");
			node.getNodeStarter().restart();
			return;
		}else if (request.getParam("restart").length() > 0) {
			ctx.getPageMaker().makeHead(buf, "Node Restart");
			buf.append("<div class=\"infobox infobox-query\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("Node Restart?\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("Are you sure you wish to restart your Freenet node?\n");
			buf.append("<form action=\"/\" method=\"post\">\n");
			buf.append("<input type=\"submit\" name=\"cancel\" value=\"Cancel\" />\n");
			buf.append("<input type=\"submit\" name=\"restartconfirm\" value=\"Restart\" />\n");
			buf.append("</form>\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			ctx.getPageMaker().makeTail(buf);
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
			return;
		} else if (request.getParam("exit").equalsIgnoreCase("true")) {
			ctx.getPageMaker().makeHead(buf, "Node Shutdown");
			buf.append("<div class=\"infobox infobox-query\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("Node Shutdown?\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("Are you sure you wish to shut down your Freenet node?\n");
			buf.append("<form action=\"/\" method=\"post\">\n");
			buf.append("<input type=\"submit\" name=\"cancel\" value=\"Cancel\" />\n");
			buf.append("<input type=\"submit\" name=\"shutdownconfirm\" value=\"Shut down\" />\n");
			buf.append("</form>\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			ctx.getPageMaker().makeTail(buf);
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
			return;
		} else if (request.isParameterSet("addbookmark")) {
			try {
				bookmarks.addBookmark(new Bookmark(request.getParam("key"), request.getParam("name")));
			} catch (MalformedURLException mue) {
				this.sendBookmarkEditPage(ctx, MODE_ADD, null, request.getParam("key"), request.getParam("name"), "Given key does not appear to be a valid Freenet key.");
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
				} else if (request.isParameterSet("edit_"+b.hashCode())) {
					this.sendBookmarkEditPage(ctx, b);
					return;
				} else if (request.isParameterSet("update_"+b.hashCode())) {
					// removing it and adding means that any USK subscriptions are updated properly
					try {
						Bookmark newbkmk = new Bookmark(request.getParam("key"), request.getParam("name"));
						bookmarks.removeBookmark(b);
						bookmarks.addBookmark(newbkmk);
					} catch (MalformedURLException mue) {
						this.sendBookmarkEditPage(ctx, MODE_EDIT, b, request.getParam("key"), request.getParam("name"), "Given key does not appear to be a valid freenet key.");
						return;
					}
					try {
						this.handleGet(new URI("/welcome/?managebookmarks"), ctx);
					} catch (URISyntaxException ex) {
						return;
					}
				}
			}
			try {
				this.handleGet(new URI("/welcome/?managebookmarks"), ctx);
			} catch (URISyntaxException ex) {
				return;
			}
		}else if(request.isParameterSet("disable")){
			UserAlert[] alerts=node.alerts.getAlerts();
			for(int i=0;i<alerts.length;i++){
				if(request.getIntParam("disable")==alerts[i].hashCode()){
					// Won't be dismissed if it's not allowed anyway
					Logger.normal(this,"Disabling the userAlert "+alerts[i].hashCode());
					alerts[i].isValid(false);

					writePermanentRedirect(ctx, "Configuration applied", "/");
				}
			}
		}else if(request.isPartSet("key")&&request.isPartSet("filename")){

				FreenetURI key = new FreenetURI(request.getPartAsString("key",128));
				String type = request.getPartAsString("content-type",128);
				if(type==null) type = "text/plain";
				ClientMetadata contentType = new ClientMetadata(type);
				
				Bucket bucket = request.getPart("filename");
				
				InsertBlock block = new InsertBlock(bucket, contentType, key);
				try {
					ctx.getPageMaker().makeHead(buf, "Insertion");
					key = this.insert(block, false);
					buf.append("<div class=\"infobox infobox-success\">\n");
					buf.append("<div class=\"infobox-header\">\n");
					buf.append("Insert Succeeded\n");
					buf.append("</div>\n");
					buf.append("<div class=\"infobox-content\">\n");
					buf.append("The key : <a href=\"/" + key.getKeyType() + "@" + key.getGuessableKey() + "\">" +
							key.getKeyType() + "@" + key.getGuessableKey() +"</a> has been inserted successfully.<br>");
				} catch (InserterException e) {
					buf.append("<div class=\"infobox infobox-error\">\n");
					buf.append("<div class=\"infobox-header\">\n");
					buf.append("Insert Failed\n");
					buf.append("</div>\n");
					buf.append("<div class=\"infobox-content\">\n");
					buf.append("Error: "+e.getMessage()+"<br>");
					if(e.uri != null)
						buf.append("URI would have been: "+e.uri+"<br>");
					int mode = e.getMode();
					if(mode == InserterException.FATAL_ERRORS_IN_BLOCKS || mode == InserterException.TOO_MANY_RETRIES_IN_BLOCKS) {
						buf.append("Splitfile-specific error:\n"+e.errorCodes.toVerboseString()+"<br>");
					}
				}
				
				ctx.getPageMaker().makeBackLink(buf,ctx);
				buf.append("<br><a href=\"/\" title=\"Node Homepage\">Homepage</a>\n");
				buf.append("</div>\n");
				buf.append("</div>\n");
				
				ctx.getPageMaker().makeTail(buf);
				writeReply(ctx, 200, "text/html", "OK", buf.toString());
				request.freeParts();
				bucket.free();
		}else {
			this.handleGet(uri, ctx);
		}
	}
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		
		HTTPRequest request = new HTTPRequest(uri);
		if (request.getParam("newbookmark").length() > 0) {
			ctx.getPageMaker().makeHead(buf, "Add A Bookmark");
			
			buf.append("<div class=\"infobox infobox-query\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("Confirm action\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("<form action=\".\" method=\"post\">\n");
			buf.append("Please confirm that you wish to add the key:<br />\n");
			buf.append("<i>"+request.getParam("newbookmark")+"</i><br />");
			buf.append("To your bookmarks, and enter the description that you would prefer:<br />\n");
			buf.append("Description:\n");
			buf.append("<input type=\"text\" name=\"name\" value=\""+HTMLEncoder.encode(request.getParam("desc"))+"\" style=\"width: 100%; \" />\n");
			buf.append("<input type=\"hidden\" name=\"key\" value=\""+HTMLEncoder.encode(request.getParam("newbookmark"))+"\" />\n");
			buf.append("<input type=\"submit\" name=\"addbookmark\" value=\"Add bookmark\" />\n");
			buf.append("</form>\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			
			ctx.getPageMaker().makeTail(buf);
		
			this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
			return;
		} else if (request.isParameterSet("managebookmarks")) {
			ctx.getPageMaker().makeHead(buf, "Bookmark Manager");
			
			// existing bookmarks
			buf.append("<div class=\"infobox infobox-normal\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("My Bookmarks\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("<form action=\".\" method=\"post\">\n");
			Enumeration e = bookmarks.getBookmarks();
			if (!e.hasMoreElements()) {
				buf.append("<i>You currently have no bookmarks defined</i>");
			} else {
				buf.append("<ul id=\"bookmarks\">\n");
				while (e.hasMoreElements()) {
					Bookmark b = (Bookmark)e.nextElement();
				
					buf.append("<li style=\"clear: right; \">\n");
					buf.append("<input type=\"submit\" name=\"delete_"+b.hashCode()+"\" value=\"Delete\" style=\"float: right; \" />\n");
					buf.append("<input type=\"submit\" name=\"edit_"+b.hashCode()+"\" value=\"Edit\" style=\"float: right; \" />\n");
					buf.append("<a href=\"/"+HTMLEncoder.encode(b.getKey())+"\">");
					buf.append(HTMLEncoder.encode(b.getDesc()));
					buf.append("</a>\n");

					buf.append("</li>\n");
				}
				buf.append("</ul>\n");
			}
			buf.append("<input type=\"hidden\" name=\"managebookmarks\" value=\"yes\" />\n");
			buf.append("</form>\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			
			// new bookmark
			this.makeBookmarkEditForm(buf, MODE_ADD, null, "", "", null);
			
			ctx.getPageMaker().makeTail(buf);
		
			this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
			return;
		}
		
		
		ctx.getPageMaker().makeHead(buf, "Freenet FProxy Homepage");
		if(node.isTestnetEnabled()) {
			buf.append("<div class=\"infobox infobox-alert\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("Testnet mode!\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("This node runs in testnet node. This WILL seriously jeopardize your anonymity!\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
		}
		
		String useragent = (String)ctx.getHeaders().get("user-agent");
		
		if (useragent != null) {
			useragent = useragent.toLowerCase();
			if (useragent.indexOf("msie") > -1 && useragent.indexOf("opera") == -1) {
				buf.append("<div class=\"infobox infobox-alert\">\n");
				buf.append("<div class=\"infobox-header\">\n");
				buf.append("Security risk!\n");
				buf.append("</div>\n");
				buf.append("<div class=\"infobox-content\">\n");
				buf.append("You appear to be using Internet Explorer. This means that some sites within Freenet may be able to compromise your anonymity!\n");
				buf.append("</div>\n");
				buf.append("</div>\n");
			}
		}

		// Alerts
		
		node.alerts.toHtml(buf);
		
		// Fetch-a-key box
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("Fetch a Key\n");
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\" id=\"keyfetchbox\">\n");
		buf.append("<form action=\"/\" method=\"get\">\n");
		buf.append("Key: <input type=\"text\" size=\"80\" name=\"key\"/>\n");
		buf.append("<input type=\"submit\" value=\"Fetch\" />\n");
		buf.append("</form>\n");
		buf.append("</div>\n");
		buf.append("</div>\n");
		
		// Bookmarks
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("My Bookmarks\n");
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		
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
		buf.append("</div>\n");
		
		// Version info and Quit Form
		buf.append("<div class=\"infobox infobox-information\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("Version\n");
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		buf.append("Freenet "+Version.nodeVersion+" Build #"+Version.buildNumber()+" r"+Version.cvsRevision);
		if(Version.buildNumber() < Version.highestSeenBuild) {
			buf.append("<br />");
			buf.append("<b>A newer version is available! (Build #"+Version.highestSeenBuild+")</b>");
		}
		buf.append("<form method=\"post\" action=\".\">\n");
		buf.append("<input type=\"hidden\" name=\"exit\" value=\"true\" /><input type=\"submit\" value=\"Shut down the node\" />\n");
		buf.append("</form>");
		if(node.isUsingWrapper()){
			buf.append("<form action=\"/\" method=\"post\">\n");
			buf.append("<input type=\"submit\" name=\"restart\" value=\"Restart the node\" />\n");
			buf.append("</form>");
		}
		buf.append("\n</div>\n");
		buf.append("</div>\n");
		
		// Activity
		buf.append("<div class=\"infobox infobox-information\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append("Current Activity\n");
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		buf.append("<ul id=\"activity\">\n"
				+ "<li>Inserts: "+this.node.getNumInserts()+"</li>\n"
				+ "<li>Requests: "+this.node.getNumRequests()+"</li>\n"
				+ "<li>Transferring Requests: "+this.node.getNumTransferringRequests()+"</li>\n"
				+ "<li>ARK Fetch Requests: "+this.node.getNumARKFetchers()+"</li>\n"
				+ "</ul>\n");
		buf.append("</div>\n");
		buf.append("</div>\n");
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	private void sendBookmarkEditPage(ToadletContext ctx, Bookmark b) throws ToadletContextClosedException, IOException {
		this.sendBookmarkEditPage(ctx, MODE_EDIT, b, b.getKey(), b.getDesc(), null);
	}
	
	private void sendBookmarkEditPage(ToadletContext ctx, int mode, Bookmark b, String origKey, String origDesc, String message) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		
		if (mode == MODE_ADD) {
			ctx.getPageMaker().makeHead(buf, "Add a Bookmark");
		} else {
			ctx.getPageMaker().makeHead(buf, "Edit a Bookmark");
		}
		
		if (message != null) {  // only used for error messages so far...
			buf.append("<div class=\"infobox infobox-error\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("An Error Occured\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append(message);
			buf.append("</div>\n");
			buf.append("</div>\n");
		}
		
		this.makeBookmarkEditForm(buf, mode, b, origKey, origDesc, message);
		
		ctx.getPageMaker().makeTail(buf);
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	private void makeBookmarkEditForm(StringBuffer buf, int mode, Bookmark b, String origKey, String origDesc, String message) {
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		if (mode == MODE_ADD) {
			buf.append("New Bookmark\n");
		} else {
			buf.append("Update Bookmark\n");
		}
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
		
		buf.append("<form action=\".\" method=\"post\">\n");
		buf.append("<div style=\"text-align: right; \">\n");
		
		buf.append("Key: \n");
		buf.append("<input type=\"text\" name=\"key\" value=\""+origKey+"\" size=\"80\" />\n");
		buf.append("<br />\n");
		
		buf.append("Description: \n");
		buf.append("<input type=\"text\" name=\"name\" value=\""+origDesc+"\" size=\"80\" />\n");
		buf.append("<br />\n");
		
		if (mode == MODE_ADD) {
			buf.append("<input type=\"submit\" name=\"addbookmark\" value=\"Add bookmark\" class=\"confirm\" />\n");
		} else {
			buf.append("<input type=\"submit\" name=\"update_"+b.hashCode()+"\" value=\"Update bookmark\" class=\"confirm\" />\n");
		}
		
		buf.append("<input type=\"submit\" value=\"Cancel\" class=\"cancel\" />\n");
		buf.append("<input type=\"hidden\" name=\"managebookmarks\" value=\"yes\" />\n");
		buf.append("</div>\n");
		buf.append("</form>\n");
		
		buf.append("</div>\n");
		buf.append("</div>\n");
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}
}
