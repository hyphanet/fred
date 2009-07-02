package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.updateableelements.UpdaterConstants;
import freenet.support.api.HTTPRequest;

/** This toadlet receives keepalives. It requires the requestId parameter. If the keepalive is failed, the request is already deleted. */
public class PushKeepaliveToadlet extends Toadlet {

	protected PushKeepaliveToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String requestId = req.getParam("requestId");
		boolean success = ((SimpleToadletServer) ctx.getContainer()).pushDataManager.keepAliveReceived(requestId);
		if (success) {
			writeHTMLReply(ctx, 200, "OK", UpdaterConstants.SUCCESS);
		} else {
			writeHTMLReply(ctx, 200, "OK", UpdaterConstants.FAILURE);
		}
	}

	@Override
	public String path() {
		return "/keepalive/";
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

}
