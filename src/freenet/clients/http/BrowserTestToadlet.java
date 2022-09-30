package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Browser Test Toadlet. Accessible from <code>http://.../test/</code>.
 *
 * Useful to test browser's capabilities:
 * <ul>
 * <li>warn the user about useless enabled features/plugins which might be dangerous</li>
 * <li>Assist the user in configuring his browser properly to surf on freenet</li>
 * <ul>
 */
public class BrowserTestToadlet extends Toadlet {

	BrowserTestToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	private final static String imgWarningMime =
		"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" +
		"X38FNlhhhyW2WGOPRTZZZbEMCAA7";

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx)
		 throws ToadletContextClosedException, IOException {
		// Yes, we need that in order to test the browser (number of connections per server)
		if (request.isParameterSet("wontload")) return;

		PageNode page = ctx.getPageMaker().getPageNode("Freenet browser testing tool", ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		if(ctx.isAllowedFullAccess())
			contentNode.addChild(ctx.getAlertManager().createSummary());

		// #### Test MIME inline
		/* for test (for allow <img src="data:...) add "; img-src 'self' data:"
		 * to freenet.clients.http.ToadletContextImpl#generateCSP return statement */
		ctx.getPageMaker().getInfobox("infobox-warning", "MIME Inline", contentNode, "mime-inline-test", true).
			addChild("img", new String[]{"src", "alt"}, new String[]{"data:image/gif;base64,"+imgWarningMime, "Your browser is probably safe."});

		// #### Test whether we can have more than 10 simultaneous connections to fproxy
		HTMLNode maxConnectionsPerServerContent = ctx.getPageMaker().getInfobox("infobox-warning", "Number of connections", contentNode, "browser-connections", true);
		maxConnectionsPerServerContent.addChild("#", "If you do not see a green picture below, your browser is probably missconfigured! Ensure it allows more than 10 connections per server.");
		for(int i = 0; i < 10 ; i++)
			maxConnectionsPerServerContent.addChild("img", "src", ".?wontload");
		maxConnectionsPerServerContent.addChild("img",
			 new String[]{"src", "alt"},
			 new String[]{"/static/themes/clean/success.png", "fail!"});

		// #### Test whether JS is available. : should do the test with pictures instead!
		ctx.getPageMaker().getInfobox("infobox-warning", "Javascript", contentNode, "javascript-test", true)
			 .addChild("div")
			 .addChild("img",
					new String[]{"id", "src", "alt"},
					new String[]{"JSTEST", "/static/themes/clean/success.png", "fail!"})
			 .addChild("script", "type", "text/javascript")
			 .addChild("%", "document.getElementById('JSTEST').src = '/static/themes/clean/warning.png';");

		this.writeHTMLReply(ctx, 200, "OK", null,pageNode.generate(), true);
	}

	@Override
	public String path() {
		return "/test/";
	}

}
