package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Decode URL Toadlet. Accessible from <code>http://.../decode/</code>.
 * 
 * Allow for Firefox about:config option keyword.URL to work properly.
 */
public class DecodeToadlet extends Toadlet {
	DecodeToadlet(HighLevelSimpleClient client, NodeClientCore c) {
		super(client);
		this.core=c;
	}
	
	final NodeClientCore core;
	
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
	    
		PageNode page = ctx.getPageMaker().getPageNode("Redirect to Decoded link", ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		
		final String requestPath = request.getPath().substring(path().length());

		//Without this it'll try to look in the current directory which will be /decode and won't work.
		String keyToFetch = "/" + requestPath;

		// This is for when a browser can't handle 301s, should very rarely (never?) be seen.
		ctx.getPageMaker().getInfobox("infobox-warning", "Decode Link", contentNode, "decode-not-redirected", true).
		    addChild("a", "href", keyToFetch, "Click Here to be re-directed");

		this.writeHTMLReply(ctx, 301, "Moved Permanently\nLocation: "+ keyToFetch, pageNode.generate());
	}

	@Override
	public String path() {
		return "/decode/";
	}

}
