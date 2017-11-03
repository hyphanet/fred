/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.NoSuchElementException;

import javax.naming.SizeLimitExceededException;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;

/**
 * A page consisting entirely of useralerts.
 * @author toad
 */
public class UserAlertsToadlet extends Toadlet {

	UserAlertsToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
        if(!ctx.checkFullAccess(this))
            return;

		PageNode page = ctx.getPageMaker().getPageNode(l10n("title"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		HTMLNode alertsNode = ctx.getAlertManager().createAlerts(false);
		if (alertsNode.getFirstTag() == null) {
			alertsNode = new HTMLNode("div", "class", "infobox");
			alertsNode.addChild("div", "class", "infobox-content").addChild("div", NodeL10n.getBase().getString("UserAlertsToadlet.noMessages"));
		}
		contentNode.addChild(alertsNode);

		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {

		MultiValueTable<String, String> headers = new MultiValueTable<String, String>();

		if (request.isPartSet("dismiss-user-alert")) {
			int userAlertHashCode = request.getIntPart("disable", -1);
			ctx.getAlertManager().dismissAlert(userAlertHashCode);
		}
		
		
		String redirect;
		try {
			redirect = request.getPartAsStringThrowing("redirectToAfterDisable", 1024);
		} catch (SizeLimitExceededException | NoSuchElementException e) {
			redirect = ".";
		}
		// hard whitelist of allowed origins to avoid https://www.owasp.org/index.php/Unvalidated_Redirects_and_Forwards_Cheat_Sheet
		// TODO: Parse the URL to ensure that it is a valid fproxy URL
		if (!("/alerts/".equals(redirect) ||
		      "/".equals(redirect) ||
		      "/#bookmarks".equals(redirect))) {
		    redirect = ".";
		}
		headers.put("Location", redirect);
		
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
	}


	protected String l10n(String name) {
		return NodeL10n.getBase().getString("UserAlertsToadlet."+name);
	}

	@Override
	public String path() {
		return "/alerts/";
	}

}
