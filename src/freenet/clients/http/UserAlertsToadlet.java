/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * A page consisting entirely of useralerts.
 * @author toad
 */
public class UserAlertsToadlet extends Toadlet {
	
	UserAlertsToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.node = node;
		this.alerts = core.alerts;
	}
	
	private UserAlertManager alerts;
	private Node node;

	@Override
	public String supportedMethods() {
		return "GET";
	}
	
	@Override
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if (!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
        HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("titleWithName", "name", node.getMyName()), ctx);
        HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
        contentNode.addChild(alerts.createAlerts(ctx));
        
        writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	protected String l10n(String name, String pattern, String value) {
		return L10n.getString("UserAlertsToadlet."+name, pattern, value);
	}
	
}
