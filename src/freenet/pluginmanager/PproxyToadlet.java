package freenet.pluginmanager;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Iterator;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.MultiValueTable;

public class PproxyToadlet extends Toadlet {
	private PluginManager pm = null;

	public PproxyToadlet(HighLevelSimpleClient client, PluginManager pm) {
		super(client);
		this.pm = pm;
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {
		
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
		
		if (request.isParameterSet("load")) {
			pm.startPlugin(request.getParam("load"));
			//writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Loading plugin", "Loading plugin...", ".", 5));
			MultiValueTable headers = new MultiValueTable();
			
			headers.put("Location", ".");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		} else {
			// Ignore
			MultiValueTable headers = new MultiValueTable();
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
			ctx.getPageMaker().makeHead(out, "Plugin List");
			out.append("<table style=\"border: 1pt solid #c0c0c0;\">");
			out.append("  <tr>\n");
			out.append("    <th>Name</th>\n");
			out.append("    <th>ID</th>\n");
			out.append("    <th>Started</th>\n");
			out.append("    <th></th>\n");
			out.append("  </tr>\n");
			Iterator it = pm.getPlugins().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
				out.append("  <tr>\n");
				out.append("    <td style=\"border: 1pt solid #c0c0c0;\">" + pi.getPluginClassName() + "</td>\n");
				out.append("    <td style=\"border: 1pt solid #c0c0c0;\">" + pi.getThreadName() + "</td>\n");
				out.append("    <td style=\"border: 1pt solid #c0c0c0;\">" + (new Date(pi.getStarted())) + "</td>\n");
				out.append("    <td style=\"border: 1pt solid #c0c0c0;\">");
				if (pi.isPproxyPlugin())
					out.append("&nbsp;<a href=\""+pi.getPluginClassName()+"/\">[VISIT]</a>&nbsp;");
				out.append("&nbsp;<a href=\"?remove="+pi.getThreadName()+"\">[UNLOAD]</a>&nbsp;");
				out.append("&nbsp;<a href=\"?reload="+pi.getThreadName()+"\">[RELOAD]</a>&nbsp;");
				out.append("</td>\n");
				out.append("  </tr>\n");
			}
			
			if (pm.getPlugins().isEmpty()) {
				out.append("<tr>\n");
				out.append("<td colspan=\"4\"\n");
				out.append("<i>No plugins loaded</i>\n");
				out.append("</td>\n");
				out.append("</tr>\n");
			}
			
			out.append("</table>");
			//String ret = "<hr/>" + out.toString();
			//ret = pm.dumpPlugins().replaceAll(",", "\n&nbsp; &nbsp; ").replaceAll("\"", " \" ");
			/*if (ret.length() < 6)
				ret += "<i>No plugins loaded</i>\n";
			ret += "<hr/>";*/
			
			
			// Obsolete
			//out.append("<form method=\"get\"><div>Remove plugin: (enter ID) <input type=\"text\" name=\"remove\" size=40/><input type=\"submit\" value=\"Remove\"/></div></form>\n");
			out.append("<form method=\"post\" action=\".\"><div>Load plugin: <input type=\"text\" name=\"load\" size=\"40\"/><input type=\"submit\" value=\"Load\" /></div></form>\n");
			ctx.getPageMaker().makeTail(out);
			writeReply(ctx, 200, "text/html", "OK", out.toString());
		} else if (request.isParameterSet("remove")) {
			pm.killPlugin(request.getParam("remove"));
			
			MultiValueTable headers = new MultiValueTable();
			
			headers.put("Location", ".");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			//writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Removing plugin", "Removing plugin...", ".", 5));
		} else if (request.isParameterSet("reload")) {
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
				this.sendErrorPage(ctx, 404, "Plugin not found", "The specified plugin could not be located in order to reload it.");
				//writeReply(ctx, 200, "text/html", "OK", mkForwardPage(ctx,"Error", "Plugin not found...", ".", 5));
			} else {
				pm.killPlugin(request.getParam("reload"));
				pm.startPlugin(fn);
				
				MultiValueTable headers = new MultiValueTable();
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			}
		}
	}
}
