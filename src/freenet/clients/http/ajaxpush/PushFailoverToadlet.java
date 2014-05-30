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
import freenet.clients.http.updateableelements.UpdaterConstants;

import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

import java.net.URI;

/** A toadlet that the client can use for push failover. It requires the requestId and originalRequestId parameter. */
public class PushFailoverToadlet extends Toadlet {
    private static volatile boolean logMINOR;

    static {
        Logger.registerClass(PushFailoverToadlet.class);
    }

    public PushFailoverToadlet(HighLevelSimpleClient client) {
        super(client);
    }

    public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
            throws ToadletContextClosedException, IOException, RedirectException {
        String requestId = req.getParam("requestId");
        String originalRequestId = req.getParam("originalRequestId");
        boolean result = ((SimpleToadletServer) ctx.getContainer()).pushDataManager.failover(originalRequestId,
                             requestId);

        if (logMINOR) {
            Logger.minor(this, "Failover from:" + originalRequestId + " to:" + requestId + " with result:" + result);
        }

        writeHTMLReply(ctx, 200, "OK", result ? UpdaterConstants.SUCCESS : UpdaterConstants.FAILURE);
    }

    @Override
    public String path() {
        return UpdaterConstants.failoverPath;
    }
}
