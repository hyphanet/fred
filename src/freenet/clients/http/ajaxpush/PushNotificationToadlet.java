/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http.ajaxpush;

//~--- non-JDK imports --------------------------------------------------------

import freenet.client.HighLevelSimpleClient;

import freenet.clients.http.RedirectException;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.clients.http.updateableelements.PushDataManager;
import freenet.clients.http.updateableelements.UpdaterConstants;

import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

import java.net.URI;

/** This toadlet provides notifications for clients. It will block until one is present. It requires the requestId parameter. */
public class PushNotificationToadlet extends Toadlet {
    private static volatile boolean logMINOR;

    static {
        Logger.registerClass(PushNotificationToadlet.class);
    }

    public PushNotificationToadlet(HighLevelSimpleClient client) {
        super(client);
    }

    public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
            throws ToadletContextClosedException, IOException, RedirectException {
        String requestId = req.getParam("requestId");
        PushDataManager.UpdateEvent event =
            ((SimpleToadletServer) ctx.getContainer()).pushDataManager.getNextNotification(requestId);

        if (event != null) {
            String elementRequestId = event.getRequestId();
            String elementId = event.getElementId();

            writeHTMLReply(ctx, 200, "OK",
                           UpdaterConstants.SUCCESS + ":" + Base64.encodeStandard(elementRequestId.getBytes("UTF-8"))
                           + UpdaterConstants.SEPARATOR + elementId);

            if (logMINOR) {
                Logger.minor(this, "Notification got:" + event);
            }
        } else {
            writeHTMLReply(ctx, 200, "OK", UpdaterConstants.FAILURE);
        }
    }

    @Override
    public String path() {
        return UpdaterConstants.notificationPath;
    }
}
