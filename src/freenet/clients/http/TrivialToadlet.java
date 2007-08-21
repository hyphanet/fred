package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.HTMLEncoder;
import freenet.support.api.HTTPRequest;

public class TrivialToadlet extends Toadlet {

	TrivialToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String fetched = uri.toString();
		String encFetched = HTMLEncoder.encode(fetched);
		String reply = "<html><head><title>You requested "+encFetched+
			"</title></head><body>You fetched <a href=\""+encFetched+"\">"+
			encFetched+"</a>.</body></html>";
		this.writeHTMLReply(ctx, 200, "OK", reply);
	}
	
	public String supportedMethods() {
		return "GET";
	}
}
