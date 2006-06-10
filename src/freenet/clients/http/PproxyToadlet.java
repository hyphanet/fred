package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Iterator;

import freenet.client.HighLevelSimpleClient;
import freenet.node.Node;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.MultiValueTable;

public class PproxyToadlet extends Toadlet {
	private final PluginManager pm;
	private final Node node;

	public PproxyToadlet(HighLevelSimpleClient client, PluginManager pm, Node n) {
		super(client);
		this.pm = pm;
		this.node = n;
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {
		
		// FIXME this is archaic! Make it use the direct bucket constructor!
		
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, plugin servlet limited to 1MB");
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
		MultiValueTable headers = new MultiValueTable();
		
		String pass = request.getParam("formPassword");
		if(pass == null || !pass.equals(node.formPassword)) {
			MultiValueTable hdrs = new MultiValueTable();
			headers.put("Location", "/queue/");
			ctx.sendReplyHeaders(302, "Found", hdrs, null, 0);
			return;
		}
		
		if (request.isParameterSet("load")) {
			pm.startPlugin(request.getParam("load"));
			//writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Loading plugin", "Loading plugin...", ".", 5));
	
			headers.put("Location", ".");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}if (request.isParameterSet("cancel")){
			headers.put("Location", "/plugins/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}if (request.getParam("unloadconfirm").length() > 0) {
			pm.killPlugin(request.getParam("unloadconfirm"));
			ctx.getPageMaker().makeHead(buf, "Plugins");
			buf.append("<div class=\"infobox infobox-success\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("Plugin Unloaded\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("The plugin " + HTMLEncoder.encode(request.getParam("remove")) + " has been unloaded.<br /><a href=\"/plugins/\">Return to Plugins Page</a>\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			ctx.getPageMaker().makeTail(buf);
				
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
			return;
		}if (request.getParam("unload").length() > 0) {
			ctx.getPageMaker().makeHead(buf, "Plugins");
			buf.append("<div class=\"infobox infobox-query\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("Unload Plugin?\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("Are you sure you wish to unload " + HTMLEncoder.encode(request.getParam("unload")) + "?\n");
			buf.append("<form action=\"/plugins/\" method=\"post\">\n");
			buf.append("<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">");
			buf.append("<input type=\"submit\" name=\"cancel\" value=\"Cancel\" />\n");
			buf.append("<input type=\"hidden\" name=\"unloadconfirm\" value=\"" + HTMLEncoder.encode(request.getParam("unload")) + "\">\n");
			buf.append("<input type=\"submit\" name=\"confirm\" value=\"Unload\" />\n");
			buf.append("</form>\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			ctx.getPageMaker().makeTail(buf);
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
			return;
		}else if (request.getParam("reload").length() > 0) {
			String fn = null;
			Iterator it = pm.getPlugins().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
				if (pi.getThreadName().equals(request.getParam("reload"))) {
					fn = pi.getFilename();
					break;
				}
			}
			
			if (fn == null) {
				this.sendErrorPage(ctx, 404, "Plugin Not Found", "The specified plugin could not be located in order to reload it.");
				//writeReply(ctx, 200, "text/html", "OK", mkForwardPage(ctx,"Error", "Plugin not found...", ".", 5));
			} else {
				pm.killPlugin(request.getParam("reload"));
				pm.startPlugin(fn);
				
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			}
			return;
		}else {
			// Ignore
			headers.put("Location", ".");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		}

	}
	
	public void handleGet(URI uri, ToadletContext ctx)
			throws ToadletContextClosedException, IOException {
		//String basepath = "/plugins/";
		HTTPRequest request = new HTTPRequest(uri);
		String path = request.getPath();

		// remove leading / and 'plugins/' from path
		if(path.startsWith("/"))
			path = path.substring(1);
		path = path.substring("plugins/".length());
		Logger.minor(this, "Pproxy fetching "+path);
		try {
			if (path.equals("")) {
				this.showPluginList(ctx, request);
			} else {
				// split path into plugin class name and 'data' path for plugin
				int to = path.indexOf("/");
				String plugin, data;
				if (to == -1) {
					plugin = path;
					data = "";
				} else {
					plugin = path.substring(0, to);
					data = path.substring(to + 1);
				}
				
				//pm.handleHTTPGet(plugin, data);
				
				// create a new request with the 'data' path and pass it to the plugin 
				request = new HTTPRequest(data, uri.getRawQuery());
				//writeReply(ctx, 200, "text/html", "OK", mkPage("plugin", pm.handleHTTPGet(plugin, data)));
				writeReply(ctx, 200, "text/html", "OK", pm.handleHTTPGet(plugin, request));

				
			}
			
			//FetchResult result = fetch(key);
			//writeReply(ctx, 200, result.getMimeType(), "OK", result.asBucket());
			
		} catch (PluginHTTPException ex) {
			// TODO: make it into html
			writeReply(ctx, ex.getCode(), ex.getMimeType(), ex.getDesc(), ex.getReply());
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			String msg = "<html><head><title>Internal Error</title></head><body><h1>Internal Error: please report</h1><pre>";
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			t.printStackTrace(pw);
			pw.flush();
			msg = msg + sw.toString() + "</pre></body></html>";
			this.writeReply(ctx, 500, "text/html", "Internal Error", msg);
		}
	}

	private void showPluginList(ToadletContext ctx, HTTPRequest request) throws ToadletContextClosedException, IOException {
		if (!request.hasParameters()) {
			StringBuffer out = new StringBuffer();
			ctx.getPageMaker().makeHead(out, "Plugins");
			//
			out.append("<div class=\"infobox infobox-normal\">\n");
			out.append("<div class=\"infobox-header\">\n");
			out.append("Plugin List\n");
			out.append("</div>\n");
			out.append("<div class=\"infobox-content\">\n");
			//
			out.append("<table class=\"plugins\">");
			out.append("<tr><th>Classname</th><th>Internal ID</th><th>Started at</th><th></th></tr>\n");
			if (pm.getPlugins().isEmpty()) {
				out.append("<tr><td colspan=\"4\">No plugins loaded</td></tr>\n");
			}
			else {
				Iterator it = pm.getPlugins().iterator();
				while (it.hasNext()) {
					PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
					out.append("<tr>");
					out.append("<td>" + pi.getPluginClassName() + "</td>");
					out.append("<td>" + pi.getThreadName() + "</td>");
					out.append("<td>" + (new Date(pi.getStarted())) + "</td>");
					out.append("<td>");
					if (pi.isPproxyPlugin()) {
						out.append("<form method=\"get\" action=\"" + pi.getPluginClassName() + "\">" +
								"<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">"+
								"<input type=\"submit\" value=\"Visit\"></form>");
					}
					out.append("<form method=\"post\" action=\".\">" +
							"<input type=\"hidden\" name=\"unload\" value=\"" + pi.getThreadName() + "\" />"+
							"<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">"+
							"<input type=\"submit\" value=\"Unload\"></form>");
					out.append("<form method=\"post\" action=\".\">" +
							"<input type=\"hidden\" name=\"reload\" value=\"" + pi.getThreadName() + "\" />"+
							"<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">"+
							"<input type=\"submit\" value=\"Reload\"></form>");
					out.append("</td></tr>\n");
				}
			}
			out.append("</table>");
			//String ret = "<hr/>" + out.toString();
			//ret = pm.dumpPlugins().replaceAll(",", "\n&nbsp; &nbsp; ").replaceAll("\"", " \" ");
			/*if (ret.length() < 6)
				ret += "<i>No plugins loaded</i>\n";
			ret += "<hr/>";*/
			
			
			// Obsolete
			//out.append("<form method=\"get\"><div>Remove plugin: (enter ID) <input type=\"text\" name=\"remove\" size=40/><input type=\"submit\" value=\"Remove\"/></div></form>\n");
			out.append("<form method=\"post\" action=\".\">" +
					"<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">"+
					"<div>Load plugin: <input type=\"text\" name=\"load\" size=\"40\"/><input type=\"submit\" value=\"Load\" /></div></form>\n");
			//
			out.append("</div>\n");
			out.append("</div>\n");
			//
			ctx.getPageMaker().makeTail(out);
			writeReply(ctx, 200, "text/html", "OK", out.toString());
		} 
	}
}
