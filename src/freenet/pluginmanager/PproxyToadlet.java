package freenet.pluginmanager;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;



import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Logger;

public class PproxyToadlet extends Toadlet {
	private PluginManager pm = null;

	public PproxyToadlet(HighLevelSimpleClient client, PluginManager pm) {
		super(client);
		this.pm = pm;
	}

	/**
	 * TODO: Remove me eventually!!!!
	 * 
	 * @param title
	 * @param content
	 * @return
	 */
	private String mkPage(String title, String content) {
		if (content == null) content = "null";
		return "<html><head><title>" + title + "</title></head><body><h1>"+
		title +"</h1>" + content.replaceAll("\n", "<br/>\n") + "</body>";
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
		String ks = uri.toString();
		if(ks.startsWith("/"))
			ks = ks.substring(1);
		ks = ks.substring("plugins/".length());
		Logger.minor(this, "Pproxy fetching "+ks);
		try {
			if (ks.equals("")) {
				String ret = "<hr/>";
				ret = pm.dumpPlugins().replaceAll(",", "\n&nbsp; &nbsp; ").replaceAll("\"", " \" ");
				if (ret.length() < 6)
					ret += "<i>No plugins loaded</i>\n";
				ret += "<hr/>";
				ret += "<form method=\"GET\">Remove plugin: (enter ID) <input type=text name=\"remove\" size=40/><input type=submit value=\"Remove\"/></form>\n";
				ret += "<form method=\"GET\">Load plugin: <input type=text name=\"load\" size=40/><input type=submit value=\"Load\" /></form>\n";
				writeReply(ctx, 200, "text/html", "OK", mkPage("Plugin list", ret));
			} else if (ks.startsWith("?remove=")) {
				pm.killPlugin(ks.substring("?remove=".length()));
				writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Removing plugin", "Removing plugin...", ".", 5));
			} else if (ks.startsWith("?load=")) {
				pm.startPlugin(ks.substring("?load=".length()));
				writeReply(ctx, 200, "text/html", "OK", mkForwardPage("Loading plugin", "Loading plugin...", ".", 5));
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
				
				//writeReply(ctx, 200, "text/html", "OK", mkPage("plugin", pm.handleHTTPGet(plugin, data)));
				writeReply(ctx, 200, "text/html", "OK", pm.handleHTTPGet(plugin, data));
	

				
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

	public void handlePut(URI uri, Bucket data, ToadletContext ctx)
			throws ToadletContextClosedException, IOException {
		String notSupported = "<html><head><title>Not supported</title></head><body>"+
		"Operation not supported</body>";
		// FIXME should be 405? Need to let toadlets indicate what is allowed maybe in a callback?
		super.writeReply(ctx, 200, "text/html", "OK", notSupported);
	}

}
