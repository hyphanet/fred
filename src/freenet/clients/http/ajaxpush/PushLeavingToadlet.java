package freenet.clients.http.ajaxpush;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.clients.http.updateableelements.UpdaterConstants;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * This toadlet allows the client to notify the server about page leaving. All of it's data is then erased, it's elements disposed, and notifications removed. It needs the
 * requestId parameter.
 */
public class PushLeavingToadlet extends Toadlet {

	private static volatile boolean	logMINOR;

	static {
		Logger.registerClass(PushLeavingToadlet.class);
	}

	public PushLeavingToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String requestId = req.getParam("requestId");
		boolean deleted = ((SimpleToadletServer) ctx.getContainer()).pushDataManager.leaving(requestId);
		if (logMINOR) {
			Logger.minor(this, "Page leaving. requestid:" + requestId + " deleted:" + deleted);
		}
		writeHTMLReply(ctx, 200, "OK", UpdaterConstants.SUCCESS);
	}

	@Override
	public String path() {
		return UpdaterConstants.leavingPath;
	}

}
