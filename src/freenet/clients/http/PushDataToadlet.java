package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class PushDataToadlet extends Toadlet {

	public static final String SEPARATOR=":";
	
	protected PushDataToadlet(HighLevelSimpleClient client) {
		super(client);
	}
	
	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		String updater="replacerUpdater";
		String id="progressbar";
		String newContent="abc";
		
		writeHTMLReply(ctx, 200, "OK", "SUCCESS:"+Base64.encodeStandard(updater.getBytes())+SEPARATOR+Base64.encodeStandard(id.getBytes())+SEPARATOR+Base64.encodeStandard(newContent.getBytes()));
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
