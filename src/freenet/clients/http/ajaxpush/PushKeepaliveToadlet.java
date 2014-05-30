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

/** This toadlet receives keepalives. It requires the requestId parameter. If the keepalive is failed, the request is already deleted. */
public class PushKeepaliveToadlet extends Toadlet {
    private static volatile boolean logMINOR;

    static {
        Logger.registerClass(PushKeepaliveToadlet.class);
    }

    public PushKeepaliveToadlet(HighLevelSimpleClient client) {
        super(client);
    }

    public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
            throws ToadletContextClosedException, IOException, RedirectException {
        String requestId = req.getParam("requestId");

        if (logMINOR) {
            Logger.minor(this, "Got keepalive:" + requestId);
        }

        boolean success = ((SimpleToadletServer) ctx.getContainer()).pushDataManager.keepAliveReceived(requestId);

        if (success) {
            writeHTMLReply(ctx, 200, "OK", UpdaterConstants.SUCCESS);
        } else {
            writeHTMLReply(ctx, 200, "OK", UpdaterConstants.FAILURE);
        }
    }

    @Override
    public String path() {
        return UpdaterConstants.keepalivePath;
    }
}
