package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;

public class TrivialToadlet extends Toadlet {

	TrivialToadlet(HighLevelSimpleClient client, String CSSName) {
		super(client, CSSName);
	}

	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String fetched = uri.toString();
		String encFetched = HTMLEncoder.encode(fetched);
		String reply = "<html><head><title>You requested "+encFetched+
			"</title></head><body>You fetched <a href=\""+encFetched+"\">"+
			encFetched+"</a>.</body></html>";
		this.writeReply(ctx, 200, "text/html", "OK", reply);
	}
	
	public String supportedMethods() {
		return "GET";
	}
}
