package freenet.clients.http.ajaxpush;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.clients.http.updateableelements.TesterElement;
import freenet.clients.http.updateableelements.XmlAlertElement;
import freenet.support.api.HTTPRequest;

/** This toadlet provides a simple page with pushed elements, making it suitable for automated tests. */
public class PushTesterToadlet extends Toadlet {

	public PushTesterToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		PageNode pageNode = ctx.getPageMaker().getPageNode("Push tester", false, ctx);
		pageNode.content.addChild(new XmlAlertElement(ctx));
		for (int i = 0; i < 600; i++) {
			pageNode.content.addChild(new TesterElement(ctx, String.valueOf(i), 100));
		}
		writeHTMLReply(ctx, 200, "OK", pageNode.outer.generate());
	}

	@Override
	public String path() {
		return "/pushtester/";
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

}
