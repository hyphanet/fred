package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

/**
 * Browser Test Toadlet.
 * Usefull to test browser's capabilities :
 *  	* warn the user about useless enabled features/plugins wich might be dangerous
 *  	* Assist the user in configuring his browser properly to surf on freenet
 */
public class BrowserTestToadlet extends Toadlet {
	BrowserTestToadlet(HighLevelSimpleClient client, NodeClientCore c) {
		super(client);
		this.core=c;
	}
	
	final NodeClientCore core;
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		final boolean advancedEnabled = core.isAdvancedDarknetEnabled();
		HTTPRequest request = new HTTPRequest(uri);
		
		// Yes, we need that in order to test the browser (number of connections per server)
		if (request.isParameterSet("wontload")) return;
		
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Freenet browser testing tool");
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		contentNode.addChild(core.alerts.createSummary());
		
		// #### Test whether we can have more than 10 simultaneous connections to fproxy
		HTMLNode maxConnectionsPerServerBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", "Number of connections"));
		HTMLNode maxConnectionsPerServerContent = ctx.getPageMaker().getContentNode(maxConnectionsPerServerBox);
		maxConnectionsPerServerContent.addChild("#", "If you do not see a green picture below, your browser is probably missconfigured! Ensure it allows more than 10 connections per server.");
		for(int i = 0; i < 10 ; i++)
			maxConnectionsPerServerContent.addChild("img", "src", ".?wontload");
		maxConnectionsPerServerContent.addChild("img", new String[]{"src", "alt"}, new String[]{"/static/themes/clean/success.gif", "fail!"});

		// #### Test whether JS is aviable. : should do the test with pictures insteed!
		HTMLNode jsTestBox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", "Javascript"));
		HTMLNode jsTestContent= ctx.getPageMaker().getContentNode(jsTestBox);
		HTMLNode jsTest = jsTestContent.addChild("div");
		jsTest.addChild("script", "language", "javascript", "document.write('Your browser has JavaScript support enabled: it's not necessary to run freenet and should be disabled.');");
		
		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}
	
	public String supportedMethods() {
		return "GET";
	}

}
