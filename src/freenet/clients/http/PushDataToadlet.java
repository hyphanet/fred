package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.api.HTTPRequest;

public class PushDataToadlet extends Toadlet {

	protected PushDataToadlet(HighLevelSimpleClient client) {
		super(client);
	}
	
	private static int szamlalo=0;
	
	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		writeHTMLReply(ctx, 200, "OK", ""+szamlalo++);
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
