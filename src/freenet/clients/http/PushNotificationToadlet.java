package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.updateableelements.PushDataManager;
import freenet.clients.http.updateableelements.UpdaterConstants;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import static freenet.clients.http.PushDataToadlet.SEPARATOR;

/** This toadlet provides notifications for clients. It will block until one is present. It requires the requestId parameter. */
public class PushNotificationToadlet extends Toadlet {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(PushNotificationToadlet.class);
	}
	
	protected PushNotificationToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String requestId = req.getParam("requestId");
		PushDataManager.UpdateEvent event = ((SimpleToadletServer) ctx.getContainer()).pushDataManager.getNextNotification(requestId);
		if (event != null) {
			String elementRequestId = event.getRequestId();
			String elementId = event.getElementId();
			writeHTMLReply(ctx, 200, "OK", UpdaterConstants.SUCCESS + ":" + Base64.encodeStandard(elementRequestId.getBytes()) + SEPARATOR + elementId);
			if(logMINOR){
				Logger.minor(this,"Notification got:"+event);
			}
		} else {
			writeHTMLReply(ctx, 200, "OK", UpdaterConstants.FAILURE);
		}
	}

	@Override
	public String path() {
		return UpdaterConstants.notificationPath;
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

}
