package freenet.clients.http;

import static freenet.clients.http.PushDataToadlet.SEPARATOR;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.updateableelements.PushDataManager;
import freenet.clients.http.updateableelements.UpdaterConstants;
import freenet.support.Base64;
import freenet.support.api.HTTPRequest;

public class PushNotificationToadlet extends Toadlet {

	protected PushNotificationToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		PushDataManager.UpdateEvent event = ((SimpleToadletServer) ctx.getContainer()).pushDataManager.getNextNotification();
		String requestId = event.getRequestId();
		String elementId = event.getElementId();
		writeHTMLReply(ctx, 200, "OK", UpdaterConstants.SUCCESS+":" + Base64.encodeStandard(requestId.getBytes()) + SEPARATOR +elementId);
	}

	@Override
	public String path() {
		return "/pushnotifications/";
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

}
