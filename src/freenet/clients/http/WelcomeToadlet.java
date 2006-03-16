package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.client.HighLevelSimpleClient;
import freenet.node.Node;
import freenet.node.Version;
import freenet.pluginmanager.HTTPRequest;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;

public class WelcomeToadlet extends Toadlet {
	Node node;

	WelcomeToadlet(HighLevelSimpleClient client, Node n, CSSNameCallback CSSName) {
		super(client, CSSName);
		this.node = n;
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
		
		if(request.hasParameters() && request.getParam("exit").equalsIgnoreCase("true")){	
			System.out.println("Goodbye.");
			writeReply(ctx, 200, "text/html", "OK", mkForwardPage(ctx, "Shutting down the node", "" , "/", 5));
			System.exit(0);
		}
		
	}
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		HTTPRequest request = new HTTPRequest(uri);
		
		
		String name = "Freenet FProxy Homepage";
		if(node.isTestnetEnabled())
			name = name +"<br><font color=red>WARNING: TESTNET MODE ENABLED</font>";
		ctx.getPageMaker().makeHead(buf, name, getCSSName());
		
		// Version info
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>Version</h2>");
		buf.append("Freenet version "+Version.nodeVersion+" build #"+Version.buildNumber());
		if(Version.buildNumber() < Version.highestSeenBuild) {
			buf.append("<br />");
			buf.append("<b>A newer version is available! (Build #"+Version.highestSeenBuild+")</b>");
		}
		buf.append("</div>\n");
		
		// Fetch-a-key box
		buf.append("<br style=\"clear: all; \" />\n");
		buf.append("<form action=\"/\" method=\"get\">\n");
		buf.append("<div class=\"infobox\">\n");
		buf.append("<h2>Fetch a Key</h2>\n");
		buf.append("Key: <input type=\"text\" size=\"80\" name=\"key\"/>\n");
		buf.append("<input type=\"submit\" value=\"Fetch\" />\n");
		buf.append("</div>\n");
		buf.append("</form>\n");
		
		// Quit Form
		buf.append("<div class=\"exit\" target=\".\">\n");
		buf.append("<form method=\"post\">\n");
		buf.append("<input type=\"hidden\" name=\"exit\" value=\"true\"><input type=\"submit\" value=\"Shutdown the node\">\n");
		buf.append("</form>\n");
		buf.append("</div>\n");
		
		// Activity
		buf.append("<ul id=\"activity\">\n"
				+ "<li>Inserts: "+this.node.getNumInserts()+"</li>\n"
				+ "<li>Requests: "+this.node.getNumRequests()+"</li>\n"
				+ "<li>Transferring Requests: "+this.node.getNumTransferringRequests()+"</li>\n"
				+ "</ul>\n");
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	public String supportedMethods() {
		return "GET";
	}
}