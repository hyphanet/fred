package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.api.HTTPRequest;

public class PushNotificationToadlet extends Toadlet {

	protected PushNotificationToadlet(HighLevelSimpleClient client) {
		super(client);
	}
	
	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		// TODO Auto-generated method stub
		super.handleGet(uri, req, ctx);
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
