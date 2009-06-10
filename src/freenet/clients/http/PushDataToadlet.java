package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class PushDataToadlet extends Toadlet {

	public static final String	SEPARATOR	= ":";

	protected PushDataToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String requestId = req.getParam("requestId");
		String elementId = req.getParam("elementId");
		HTMLNode node = ((SimpleToadletServer) ctx.getContainer()).pushDataManager.getRenderedElement(requestId, elementId);
		writeHTMLReply(ctx, 200, "OK", "SUCCESS:" + Base64.encodeStandard(node.generateChildren().getBytes()));
	}

	@Override
	public String path() {
		return "/pushdata/";
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

}
