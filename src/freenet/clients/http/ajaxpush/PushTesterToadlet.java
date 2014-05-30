/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http.ajaxpush;

//~--- non-JDK imports --------------------------------------------------------

import freenet.client.HighLevelSimpleClient;

import freenet.clients.http.PageMaker.RenderParameters;
import freenet.clients.http.PageNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.clients.http.updateableelements.TesterElement;

import freenet.support.api.HTTPRequest;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

import java.net.URI;

/** This toadlet provides a simple page with pushed elements, making it suitable for automated tests. */
public class PushTesterToadlet extends Toadlet {
    public PushTesterToadlet(HighLevelSimpleClient client) {
        super(client);
    }

    public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
            throws ToadletContextClosedException, IOException, RedirectException {
        PageNode pageNode = ctx.getPageMaker().getPageNode("Push tester", ctx,
                                new RenderParameters().renderNavigationLinks(false));

        for (int i = 0; i < 600; i++) {
            pageNode.content.addChild(new TesterElement(ctx, String.valueOf(i), 100));
        }

        writeHTMLReply(ctx, 200, "OK", pageNode.outer.generate());
    }

    @Override
    public String path() {
        return "/pushtester/";
    }
}
