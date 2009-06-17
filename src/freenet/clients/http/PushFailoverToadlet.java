package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.updateableelements.UpdaterConstants;
import freenet.support.api.HTTPRequest;

public class PushFailoverToadlet extends Toadlet {

	protected PushFailoverToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String requestId = req.getParam("requestId");
		String originalRequestId = req.getParam("originalRequestId");
		boolean result = ((SimpleToadletServer) ctx.getContainer()).pushDataManager.failover(originalRequestId, requestId);
		writeHTMLReply(ctx, 200, "OK", result ? UpdaterConstants.SUCCESS : UpdaterConstants.FAILURE);
	}

	@Override
	public String path() {
		return "/failover/";
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

}
