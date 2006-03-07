package freenet.pluginmanager;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.Date;
import java.util.Iterator;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.clients.http.PageMaker;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Logger;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.MultiValueTable;

public class PproxyToadlet extends Toadlet {
	private PluginManager pm = null;

	public PproxyToadlet(HighLevelSimpleClient client, PluginManager pm) {
		super(client);
		this.pm = pm;
	}
	
	public String supportedMethods() {
		return "GET";
	}

	private String mkForwardPage(String title, String content, String nextpage, int interval) {
		if (content == null) content = "null";
		return "<html><head><title>" + title + "</title>"+
		"<META HTTP-EQUIV=Refresh CONTENT=\"" + interval +
		"; URL="+nextpage+"\"></head><body><h1>" + title +
		"</h1>" + content.replaceAll("\n", "<br/>\n") + "</body>";
	}
	
	public void handleGet(URI uri, ToadletContext ctx)
			throws ToadletContextClosedException, IOException {
		//String basepath = "/plugins/";
		String ks = uri.getPath();

		
		if(ks.startsWith("/"))
			ks = ks.substring(1);
		ks = ks.substring("plugins/".length());
		Logger.minor(this, "Pproxy fetching "+ks);
		try {
			if (ks.equals("")) {
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
				out.append("<form method=\"get\" action=\".\"><div>Load plugin: <input type=\"text\" name=\"load\" size=\"40\"/><input type=\"submit\" value=\"Load\" /></div></form>\n");
				ctx.getPageMaker().makeTail(out);
				writeReply(ctx, 200, "text/html", "OK", out.toString());
			} else if (ks.startsWith("?remove=")) {
				pm.killPlugin(ks.substring("?remove=".length()));
				
				MultiValueTable headers = new MultiValueTable();
				
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				//writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Removing plugin", "Removing plugin...", ".", 5));
			} else if (ks.startsWith("?load=")) {
				pm.startPlugin(ks.substring("?load=".length()));
				//writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Loading plugin", "Loading plugin...", ".", 5));
				MultiValueTable headers = new MultiValueTable();
				
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			} else if (ks.startsWith("?reload=")) {
				String fn = null;
				Iterator it = pm.getPlugins().iterator();
				while (it.hasNext()) {
					PluginInfoWrapper pi = (PluginInfoWrapper) it.next();
					if (pi.getThreadName().equals(ks.substring("?reload=".length()))) {
						fn = pi.getFilename();
						break;
					}
				}
				
				if (fn == null) {
					writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Error", "Plugin not found...", ".", 5));
				} else {
					pm.killPlugin(ks.substring("?reload=".length()));
					pm.startPlugin(fn);
					
					MultiValueTable headers = new MultiValueTable();
					headers.put("Location", ".");
					ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				}
			} else {
				int to = ks.indexOf("/");
				String plugin, data;
				if (to == -1) {
					plugin = ks;
					data = "";
				} else {
					plugin = ks.substring(0, to);
					data = ks.substring(to + 1);
				}
				
				//pm.handleHTTPGet(plugin, data);
				
				HTTPRequest request = new HTTPRequest(data, uri.getRawQuery());
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
}
