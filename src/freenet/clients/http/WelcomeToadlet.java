package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.node.Version;
import freenet.node.Node;

public class WelcomeToadlet extends Toadlet {
	Node node;

	WelcomeToadlet(HighLevelSimpleClient client, Node n) {
		super(client);
		this.node = n;
	}

	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		
		ctx.getPageMaker().makeHead(buf, "Freenet FProxy Homepage");
		
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