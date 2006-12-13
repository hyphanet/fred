/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.client.ClientMetadata;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.clients.http.filter.GenericReadFilterCallback;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStarter;
import freenet.node.Version;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;

import freenet.frost.message.*;

public class WelcomeToadlet extends Toadlet {
	private final static int MODE_ADD = 1;
	private final static int MODE_EDIT = 2;
	private static final int MAX_URL_LENGTH = 1024 * 1024;
	private static final int MAX_KEY_LENGTH = QueueToadlet.MAX_KEY_LENGTH;
	private static final int MAX_NAME_LENGTH = 1024 * 1024;
	NodeClientCore core;
	Node node;
	SubConfig config;
	BookmarkManager bookmarks;
	
	WelcomeToadlet(HighLevelSimpleClient client, NodeClientCore core, Node node, SubConfig sc) {
		super(client);
		this.core = core;
		this.node = node;
		this.config = sc;
		this.bookmarks = core.bookmarkManager;
	}

	void redirectToRoot(ToadletContext ctx) throws ToadletContextClosedException, IOException {
		MultiValueTable headers = new MultiValueTable();
		headers.put("Location", "/");
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		return;
	}
	
	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Data exceeds 1MB limit");
			return;
		}
		
		HTTPRequest request = new HTTPRequest(uri,data,ctx);
		
		String passwd = request.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
		}
		
		if(request.getPartAsString("updateconfirm", 32).length() > 0){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			// false for no navigation bars, because that would be very silly
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node updating");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", "Node updating"));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("p").addChild("#", "The Freenet node is being updated and will self-restart. The restart process may take up to 10 minutes, because the node will try to fetch a revocation key before updating.");
			content.addChild("p").addChild("#", "Thank you for using Freenet.");
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			Logger.normal(this, "Node is updating/restarting");
			node.getNodeUpdater().arm();
			return;
		}else if (request.getPartAsString(GenericReadFilterCallback.magicHTTPEscapeString, MAX_URL_LENGTH).length()>0){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			MultiValueTable headers = new MultiValueTable();
			String url = null;
			if((request.getPartAsString("Go", 32).length() > 0))
				url = request.getPartAsString(GenericReadFilterCallback.magicHTTPEscapeString, MAX_URL_LENGTH);
			headers.put("Location", url==null ? "/" : url);
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}else if (request.getPartAsString("update", 32).length() > 0) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node Update");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-query", "Node Update"));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("p").addChild("#", "Are you sure you wish to update your Freenet node?");
			HTMLNode updateForm = ctx.addFormChild(content, "/", "updateConfirmForm");
			updateForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
			updateForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "updateconfirm", "Update" });
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		}else if(request.isPartSet("getThreadDump")) {
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Get a Thread Dump");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			if(node.isUsingWrapper()){
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("Thread Dump generation"));
				ctx.getPageMaker().getContentNode(infobox).addChild("#", "A thread dump has been generated, it's available in "+ WrapperManager.getProperties().getProperty("wrapper.logfile"));
				System.out.println("Thread Dump:");
				WrapperManager.requestThreadDump();
			}else{
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error","Thread Dump generation"));
				ctx.getPageMaker().getContentNode(infobox).addChild("#", "It's not possible to make the node generate a thread dump if you aren't using the wrapper!");
			}
			this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		}else if (request.isPartSet("addbookmark")) {
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			String key = request.getPartAsString("key", MAX_KEY_LENGTH);
			String name = request.getPartAsString("name", MAX_NAME_LENGTH);
			try {
				bookmarks.addBookmark(new Bookmark(key, name), true);
			} catch (MalformedURLException mue) {
				this.sendBookmarkEditPage(ctx, MODE_ADD, null, key, name, "Given key does not appear to be a valid Freenet key.");
				return;
			}
			
			try {
				this.handleGet(new URI("/welcome/?managebookmarks"), ctx);
			} catch (URISyntaxException ex) {
				
			}
		} else if (request.isPartSet("managebookmarks")) {
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			Enumeration e = bookmarks.getBookmarks();
			while (e.hasMoreElements()) {
				Bookmark b = (Bookmark)e.nextElement();
			
				if (request.isPartSet("delete_"+b.hashCode())) {
					bookmarks.removeBookmark(b, true);
				} else if (request.isPartSet("edit_"+b.hashCode())) {
					this.sendBookmarkEditPage(ctx, b);
					return;
				} else if (request.isPartSet("update_"+b.hashCode())) {
					// removing it and adding means that any USK subscriptions are updated properly
					String key = request.getPartAsString("key", MAX_KEY_LENGTH);
					String name = request.getPartAsString("name", MAX_NAME_LENGTH);
					try {
						Bookmark newbkmk = new Bookmark(key, name);
						bookmarks.removeBookmark(b, false);
						bookmarks.addBookmark(newbkmk, true);
					} catch (MalformedURLException mue) {
						this.sendBookmarkEditPage(ctx, MODE_EDIT, b, key, name, "Given key does not appear to be a valid freenet key.");
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
		}else if(request.isPartSet("disable")){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			UserAlert[] alerts=core.alerts.getAlerts();
			for(int i=0;i<alerts.length;i++){
				if(request.getIntPart("disable",-1)==alerts[i].hashCode()){
					UserAlert alert = alerts[i];
					// Won't be dismissed if it's not allowed anyway
					if(alert.userCanDismiss() && alert.shouldUnregisterOnDismiss()) {
						alert.onDismiss();
						Logger.normal(this,"Unregistering the userAlert "+alert.hashCode());
						core.alerts.unregister(alert);
					} else {
						Logger.normal(this,"Disabling the userAlert "+alert.hashCode());
						alert.isValid(false);
					}

					writePermanentRedirect(ctx, "Configuration applied", "/");
				}
			}
		} else if(request.isPartSet("boardname")&&(request.isPartSet("filename")||request.isPartSet("message"))) {
			// Inserting into a frost board FIN
			// boardname
			// filename
			// boardprivatekey (not needed)
			// boardpublickey (not needed) (and maybe dump it all the way)
			// innitialindex
			// sender
			// subject
			String boardName = request.getPartAsString("boardname",FrostBoard.MAX_NAME_LENGTH);
			String boardPrivateKey = request.getPartAsString("boardprivatekey",78);
			String boardPublicKey = request.getPartAsString("boardpublickey",78);
			String sender = request.getPartAsString("sender",64);
			String subject = request.getPartAsString("subject",128);
			String message = request.getPartAsString("message",1024);
			if(message.length() == 0) // back compatibility; should use message
				message = request.getPartAsString("filename", 1024);
			
			int initialIndex = 0;
			if(request.isPartSet("initialindex")) {
				try {
					initialIndex = Integer.parseInt(request.getPartAsString("initialindex",10));
				} catch(NumberFormatException e) {
					initialIndex = 0;
				}
			} else if(request.isPartSet("innitialindex")) {
				try {
					initialIndex = Integer.parseInt(request.getPartAsString("innitialindex",10));
				} catch(NumberFormatException e) {
					initialIndex = 0;
				}
			}
			
			if(noPassword) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode("Frost Instant Note insert");
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-query", "Frost Instant Note insert"));
				HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("p").addChild("#", "Do you want to insert the following Frost message?");
				HTMLNode postForm = ctx.addFormChild(content.addChild("p"), "/", "finConfirmForm"); 
				HTMLNode table = postForm.addChild("table", "align", "center");
				
				finInputRow(table, "boardname", "Target Board", boardName);
				finInputRow(table, "boardprivatekey", "Private Key", boardPrivateKey);
				finInputRow(table, "boardpublickey", "Public Key", boardPublicKey);
				finInputRow(table, "initialindex", "Index to start with", Integer.toString(initialIndex));
				finInputRow(table, "sender", "From", sender);
				finInputRow(table, "subject", "Subject", subject);
				finInputBoxRow(table, "message", "Message", message);
				
				postForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
				postForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "finconfirm", "Post" });
				writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
				return;
			}
			
			String confirm = request.getPartAsString("finconfirm", 32);
			
			if(!confirm.equals("Post")) {
				redirectToRoot(ctx);
				return;
			}
			
			FrostBoard board = null;
			if(boardPrivateKey.length()>0 && boardPublicKey.length()>0) { // keyed board
				board = new FrostBoard(boardName, boardPrivateKey, boardPublicKey);
			} else { // unkeyed or public board
				board = new FrostBoard(boardName);
			}
			FrostMessage fin = new FrostMessage("news", board, sender, subject, message);
			
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Insertion");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode content;
			try {
				FreenetURI finalKey = fin.insertMessage(this.getClientImpl(), initialIndex);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-success", "Insert Succeeded"));
				content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("#", "The message ");
				content.addChild("#", " has been inserted successfully into "+finalKey.toString());
			} catch (InserterException e) {
				HTMLNode infobox = ctx.getPageMaker().getInfobox("infobox-error", "Insert Failed");
				content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("#", "The insert failed with the message: " + e.getMessage());
				content.addChild("br");
				if (e.uri != null) {
					content.addChild("#", "The URI would have been: " + e.uri);
				}
				int mode = e.getMode();
				if((mode == InserterException.FATAL_ERRORS_IN_BLOCKS) || (mode == InserterException.TOO_MANY_RETRIES_IN_BLOCKS)) {
					content.addChild("br"); /* TODO */
					content.addChild("#", "Splitfile-specific error: " + e.errorCodes.toVerboseString());
				}
			}
			content.addChild("br");
			content.addChild("a", new String[] { "href", "title" }, new String[] { "/", "Node Homepage" }, "Homepage");
			
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			request.freeParts();
		}else if(request.isPartSet("key")&&request.isPartSet("filename")){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}

			FreenetURI key = new FreenetURI(request.getPartAsString("key",128));
			String type = request.getPartAsString("content-type",128);
			if(type==null) type = "text/plain";
			ClientMetadata contentType = new ClientMetadata(type);
			
			Bucket bucket = request.getPart("filename");
			
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Insertion");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode content;
			String filenameHint = null;
			if(key.getKeyType().equals("CHK")) {
				String[] metas = key.getAllMetaStrings();
				if(metas != null && metas.length > 1) {
					filenameHint = metas[0];
				}
			}
			InsertBlock block = new InsertBlock(bucket, contentType, key);
			try {
				key = this.insert(block, filenameHint, false);
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-success", "Insert Succeeded"));
				content = ctx.getPageMaker().getContentNode(infobox);
				String u = key.toString();
				content.addChild("#", "The key ");
				content.addChild("a", "href", '/' + u, u);
				content.addChild("#", " has been inserted successfully.");
			} catch (InserterException e) {
				HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", "Insert Failed"));
				content = ctx.getPageMaker().getContentNode(infobox);
				content.addChild("#", "The insert failed with the message: " + e.getMessage());
				content.addChild("br");
				if (e.uri != null) {
					content.addChild("#", "The URI would have been: " + e.uri);
				}
				int mode = e.getMode();
				if((mode == InserterException.FATAL_ERRORS_IN_BLOCKS) || (mode == InserterException.TOO_MANY_RETRIES_IN_BLOCKS)) {
					content.addChild("br"); /* TODO */
					content.addChild("#", "Splitfile-specific error: " + e.errorCodes.toVerboseString());
				}
			}
			
			content.addChild("br");
			content.addChild("a", new String[] { "href", "title" }, new String[] { "/", "Node Homepage" }, "Homepage");
			
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			request.freeParts();
			bucket.free();
		}else if (request.isPartSet("shutdownconfirm")) {
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			// Tell the user that the node is shutting down
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node Shutdown", false);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", "The Freenet node has been successfully shut down."));
			HTMLNode infoboxContent = ctx.getPageMaker().getContentNode(infobox);
			infoboxContent.addChild("#", "Thank you for using Freenet.");
			writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			this.node.exit("Shutdown from fproxy");
			return;
		}else if(request.isPartSet("restartconfirm")){
			if(noPassword) {
				redirectToRoot(ctx);
				return;
			}
			// Tell the user that the node is restarting
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node Restart", false);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", "The Freenet is being restarted."));
			HTMLNode infoboxContent = ctx.getPageMaker().getContentNode(infobox);
			infoboxContent.addChild("#", "Please wait while the node is being restarted. This might take up to 3 minutes. Thank you for using Freenet.");
			writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			Logger.normal(this, "Node is restarting");
			node.getNodeStarter().restart();
			return;
		}else {
			redirectToRoot(ctx);
		}
	}
	
	private void finInputBoxRow(HTMLNode table, String name, String label, String message) {
		HTMLNode row = table.addChild("tr");
		HTMLNode cell = row.addChild("td");
		// FIXME this should be in the CSS, not the generated code
		HTMLNode right = cell.addChild("div", "align", "right");
		HTMLNode bold = right.addChild("b");
		HTMLNode font = bold.addChild("font", "size", "-1");
		font.addChild("#", label);
		cell = row.addChild("td");
		cell.addChild("textarea", new String[] { "name", "rows", "cols" },
				new String[] { name, "12", "80" }).addChild("#", message);
	}

	private void finInputRow(HTMLNode table, String name, String label, String message) {
		HTMLNode row = table.addChild("tr");
		HTMLNode cell = row.addChild("td");
		// FIXME this should be in the CSS, not the generated code
		HTMLNode right = cell.addChild("div", "align", "right");
		HTMLNode bold = right.addChild("b");
		HTMLNode font = bold.addChild("font", "size", "-1");
		font.addChild("#", label);
		cell = row.addChild("td");
		cell.addChild("input", new String[] { "type", "name", "size", "value" }, 
				new String[] { "text", name, "30", message });
	}

	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		boolean advancedDarknetOutputEnabled = core.getToadletContainer().isAdvancedDarknetEnabled();
		
		HTTPRequest request = new HTTPRequest(uri);
		if (request.getParam("newbookmark").length() > 0) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Add a Bookmark");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("Confirm Bookmark Addition"));
			HTMLNode addForm = ctx.addFormChild(ctx.getPageMaker().getContentNode(infobox), "/", "bookmarkAddForm");
			addForm.addChild("#", "Please confirm that you want to add the key " + request.getParam("newbookmark") + " to your bookmarks and enter the description that you would prefer:");
			addForm.addChild("br");
			addForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", request.getParam("newbookmark") });
			addForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "name", request.getParam("desc") });
			addForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "addbookmark", "Add bookmark" });
			this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		} else if (request.getParam(GenericReadFilterCallback.magicHTTPEscapeString).length() > 0) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Link to external resources");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode warnbox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", "External link"));
			HTMLNode externalLinkForm = ctx.addFormChild(ctx.getPageMaker().getContentNode(warnbox), "/", "confirmExternalLinkForm");

			// FIXME: has request.getParam(GenericReadFilterCallback.magicHTTPEscapeString) been sanityzed ?
			final String target = request.getParam(GenericReadFilterCallback.magicHTTPEscapeString);
			externalLinkForm.addChild("#", "Please confirm that you want to go to " + target + ". WARNING: You are leaving FREENET! Clicking on this link WILL seriously jeopardize your anonymity!. It is strongly recommended not to do so!");
			externalLinkForm.addChild("br");
			externalLinkForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", GenericReadFilterCallback.magicHTTPEscapeString, target });
			externalLinkForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
			externalLinkForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "Go", "Go to the specified link" });
			this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		} else if (request.isParameterSet("managebookmarks")) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Bookmark Manager");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-normal", "My Bookmarks"));
			HTMLNode infoboxContent = ctx.getPageMaker().getContentNode(infobox);
			
			Enumeration e = bookmarks.getBookmarks();
			if (!e.hasMoreElements()) {
				infoboxContent.addChild("#", "You currently do not have any bookmarks defined.");
			} else {
				HTMLNode manageForm = ctx.addFormChild(infoboxContent, ".", "manageBookmarksForm");
				HTMLNode bookmarkList = manageForm.addChild("ul", "id", "bookmarks");
				while (e.hasMoreElements()) {
					Bookmark b = (Bookmark)e.nextElement();
				
					HTMLNode bookmark = bookmarkList.addChild("li", "style", "clear: right;"); /* TODO */
					bookmark.addChild("input", new String[] { "type", "name", "value", "style" }, new String[] { "submit", "delete_" + b.hashCode(), "Delete", "float: right;" });
					bookmark.addChild("input", new String[] { "type", "name", "value", "style" }, new String[] { "submit", "edit_" + b.hashCode(), "Edit", "float: right;" });
					bookmark.addChild("a", "href", '/' + b.getKey(), b.getDesc());
				}
				manageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "managebookmarks", "yes" });
			}
			contentNode.addChild(createBookmarkEditForm(ctx, MODE_ADD, null, "", ""));
			this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		}else if (request.isParameterSet("exit")) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node Shutdown");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-query", "Node Shutdown"));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("p").addChild("#", "Are you sure you wish to shut down your Freenet node?");
			HTMLNode shutdownForm = ctx.addFormChild(content.addChild("p"), "/", "confirmShutdownForm");
			shutdownForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
			shutdownForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "shutdownconfirm", "Shut down" });
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		}else if (request.isParameterSet("restart")) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node Restart");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-query", "Node Restart"));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("p").addChild("#", "Are you sure you want to restart your Freenet node?");
			HTMLNode restartForm = ctx.addFormChild(content.addChild("p"), "/", "confirmRestartForm");
			restartForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
			restartForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restartconfirm", "Restart" });
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Freenet FProxy Homepage of " + node.getMyName());
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

		if(node.isTestnetEnabled()) {
			HTMLNode testnetBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-alert", "Testnet Mode!"));
			HTMLNode testnetContent = ctx.getPageMaker().getContentNode(testnetBox);
			testnetContent.addChild("#", "This node runs in testnet mode. This WILL seriously jeopardize your anonymity!");
		}
		
		String useragent = (String)ctx.getHeaders().get("user-agent");
		
		if (useragent != null) {
			useragent = useragent.toLowerCase();
			if ((useragent.indexOf("msie") > -1) && (useragent.indexOf("opera") == -1)) {
				HTMLNode browserWarningBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-alert", "Security Risk!"));
				HTMLNode browserWarningContent = ctx.getPageMaker().getContentNode(browserWarningBox);
				browserWarningContent.addChild("#", "You appear to be using Microsoft Internet Explorer. This means that some sites within Freenet may be able to compromise your anonymity!");
			}
		}

		// Alerts
		contentNode.addChild(core.alerts.createAlerts());
		
		// Fetch-a-key box
		HTMLNode fetchKeyBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-normal", "Fetch a Key"));
		HTMLNode fetchKeyContent = ctx.getPageMaker().getContentNode(fetchKeyBox);
		fetchKeyContent.addAttribute("id", "keyfetchbox");
		HTMLNode fetchKeyForm = fetchKeyContent.addChild("form", new String[] { "action", "method" }, new String[] { "/", "get" });
		fetchKeyForm.addChild("#", "Key: ");
		fetchKeyForm.addChild("input", new String[] { "type", "size", "name" }, new String[] { "text", "80", "key" });
		fetchKeyForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Fetch" });
		
		// Bookmarks
		HTMLNode bookmarkBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-normal", "My Bookmarks"));
		HTMLNode bookmarkContent = ctx.getPageMaker().getContentNode(bookmarkBox);
		
		Enumeration e = bookmarks.getBookmarks();
		if (!e.hasMoreElements()) {
			bookmarkContent.addChild("#", "You currently do not have any bookmarks defined.");
		} else {
			HTMLNode bookmarkList = bookmarkContent.addChild("ul", "id", "bookmarks");
			while (e.hasMoreElements()) {
				Bookmark b = (Bookmark)e.nextElement();
				bookmarkList.addChild("li").addChild("a", "href", '/' + b.getKey(), b.getDesc());
			}
		}
		bookmarkContent.addChild("div", "id", "bookmarkedit").addChild("a", new String[] { "href", "class" }, new String[] { "?managebookmarks", "interfacelink" }, "Edit my bookmarks");
		
		// Version info and Quit Form
		HTMLNode versionBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", "Version Information & Node Control"));
		HTMLNode versionContent = ctx.getPageMaker().getContentNode(versionBox);
		versionContent.addChild("#", "Freenet " + Version.nodeVersion + " Build #" + Version.buildNumber() + " r" + Version.cvsRevision);
		versionContent.addChild("br");
		if(NodeStarter.extBuildNumber < NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER) {
			versionContent.addChild("#", "Freenet-ext Build #" + NodeStarter.extBuildNumber + '(' + NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER + ") r" + NodeStarter.extRevisionNumber);
		} else {
			versionContent.addChild("#", "Freenet-ext Build #" + NodeStarter.extBuildNumber + " r" + NodeStarter.extRevisionNumber);
		}
		versionContent.addChild("br");
		HTMLNode shutdownForm = versionContent.addChild("form", new String[] { "action", "method" }, new String[] { ".", "GET" });
		shutdownForm.addChild("input", new String[] { "type", "name" }, new String[] { "hidden", "exit" });
		shutdownForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Shutdown the node" });
		if(node.isUsingWrapper()){
			HTMLNode restartForm = versionContent.addChild("form", new String[] { "action", "method" }, new String[] { ".", "GET" });
			restartForm.addChild("input", new String[] { "type", "name" }, new String[] { "hidden", "restart" });
			restartForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restart2", "Restart the node" });
		}
		
		// Activity
		HTMLNode activityBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-information", "Current Activity"));
		HTMLNode activityContent = ctx.getPageMaker().getContentNode(activityBox);
		HTMLNode activityList = activityContent.addChild("ul", "id", "activity");
		activityList.addChild("li", "Inserts: " + node.getNumInserts());
		activityList.addChild("li", "Requests: " + node.getNumRequests());
		activityList.addChild("li", "Transferring Requests: " + node.getNumTransferringRequests());
		if (advancedDarknetOutputEnabled) {
			activityList.addChild("li", "ARK Fetch Requests: " + node.getNumARKFetchers());
		}
		
		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}
	
	private void sendBookmarkEditPage(ToadletContext ctx, Bookmark b) throws ToadletContextClosedException, IOException {
		this.sendBookmarkEditPage(ctx, MODE_EDIT, b, b.getKey(), b.getDesc(), null);
	}
	
	private void sendBookmarkEditPage(ToadletContext ctx, int mode, Bookmark b, String origKey, String origDesc, String message) throws ToadletContextClosedException, IOException {
		HTMLNode pageNode = ctx.getPageMaker().getPageNode((mode == MODE_ADD) ? "Add a Bookmark" : "Edit a Bookmark");
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		if (message != null) {  // only used for error messages so far...
			HTMLNode errorBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", "An Error Occured"));
			ctx.getPageMaker().getContentNode(errorBox).addChild("#", message);
		}
		
		contentNode.addChild(createBookmarkEditForm(ctx, mode, b, origKey, origDesc));
		
		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}
	
	private HTMLNode createBookmarkEditForm(ToadletContext ctx, int mode, Bookmark b, String origKey, String origDesc) {
		PageMaker pageMaker = ctx.getPageMaker();
		HTMLNode infobox = pageMaker.getInfobox("infobox-normal bookmark-edit", (mode == MODE_ADD) ? "New Bookmark" : "Update Bookmark");
		HTMLNode content = pageMaker.getContentNode(infobox);
		HTMLNode editForm = ctx.addFormChild(content, ".", mode == MODE_ADD ? "addBookmarkForm" : "editBookmarkForm");
		editForm.addChild("#", "Key: ");
		editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "key", origKey });
		editForm.addChild("br");
		editForm.addChild("#", "Description: ");
		editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "name", origDesc });
		editForm.addChild("br");
		if (mode == MODE_ADD) {
			editForm.addChild("input", new String[] { "type", "name", "value", "class" }, new String[] { "submit", "addbookmark", "Add bookmark", "confirm" });
		} else {
			editForm.addChild("input", new String[] { "type", "name", "value", "class" }, new String[] { "submit", "update_" + b.hashCode(), "Update bookmark", "confirm" });
		}
		editForm.addChild("input", new String[] { "type", "value", "class" }, new String[] { "submit", "Cancel", "cancel" });
		editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "managebookmarks", "yes" });
		return infobox;
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}
}
