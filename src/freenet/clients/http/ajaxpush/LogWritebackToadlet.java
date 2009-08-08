package freenet.clients.http.ajaxpush;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.clients.http.updateableelements.UpdaterConstants;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/** This toadlet is used to let the client write to the logs */
public class LogWritebackToadlet extends Toadlet {

	private static volatile boolean	logMINOR;

	static {
		Logger.registerClass(LogWritebackToadlet.class);
	}

	public LogWritebackToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if (logMINOR) {
			Logger.minor(this, "GWT:" + req.getParam("msg"));
		}
		writeHTMLReply(ctx, 200, "OK", UpdaterConstants.SUCCESS);
	}

	@Override
	public String path() {
		return UpdaterConstants.logWritebackPath;
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

}
