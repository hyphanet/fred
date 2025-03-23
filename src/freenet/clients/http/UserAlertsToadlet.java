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
import freenet.node.useralerts.AbstractNodeToNodeFileOfferUserAlert;
import freenet.node.useralerts.NodeToNodeMessageUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;

/**
 * A page consisting entirely of useralerts.
 * @author toad
 */
public class UserAlertsToadlet extends Toadlet {

	private static final String DISMISS_ALL_ALERTS_PART = "dismissAllAlerts";
	private static final String DELETE_ALL_MESSAGES_PART = "deleteAllMessages";
	private static final String REALLY_DELETE_AFFIRMED = "reallyDeleteAffirmed";

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
		} else {
			addDismissAllButtons(ctx, contentNode);
		}
		contentNode.addChild(alertsNode);

		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private void addDismissAllButtons(ToadletContext ctx, HTMLNode contentNode) {
		HTMLNode dismissAlertsContainer = contentNode.addChild("div", "class", "dismiss-all-alerts-container");
		HTMLNode dismissAlertsForm = ctx.addFormChild(dismissAlertsContainer, "", "deleteAllNotificationsForm");
		dismissAlertsForm.addChild("input",
				new String[]{"name", "type", "title", "value"},
				new String[]{ DISMISS_ALL_ALERTS_PART, "submit", l10n("dismissAlertsButtonTitle"), l10n("dismissAlertsButtonContent")});
		HTMLNode deleteMessagesForm = ctx.addFormChild(dismissAlertsContainer, "", "deleteAllNotificationsForm");
		deleteMessagesForm.addChild("input",
				new String[]{"name", "type", "title", "value"},
				new String[]{ DELETE_ALL_MESSAGES_PART, "submit", l10n("deleteMessagesButtonTitle"), l10n("deleteMessagesButtonContent")});
		String deleteMessagesReallyCheckboxId = "reallyDeleteAllMessagesCheckbox";
		deleteMessagesForm.addChild(
				"input",
				new String[]{ "type", "name", "id", "required" },
				new String[]{ "checkbox", REALLY_DELETE_AFFIRMED, deleteMessagesReallyCheckboxId, "true" });
		deleteMessagesForm.addChild("label", "for", deleteMessagesReallyCheckboxId, l10n("deleteMessagesButtonReallyDeleteLabel"));
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if (request.isPartSet("dismiss-user-alert")) {
			int userAlertHashCode = request.getIntPart("disable", -1);
			ctx.getAlertManager().dismissAlert(userAlertHashCode);
		}
		if (request.isPartSet(DISMISS_ALL_ALERTS_PART)) {
			for (UserAlert alert : ctx.getAlertManager().getAlerts()) {
				if (!alert.userCanDismiss()) {
					continue;
				}
				if (alert instanceof NodeToNodeMessageUserAlert) {
					continue; // keep all node to node messages
				}
				ctx.getAlertManager().dismissAlert(alert.hashCode());
			}
		}

		if (request.isPartSet(DELETE_ALL_MESSAGES_PART) && request.isPartSet(REALLY_DELETE_AFFIRMED)) {
			for (UserAlert alert : ctx.getAlertManager().getAlerts()) {
				if (!(alert instanceof NodeToNodeMessageUserAlert)) {
					continue;
				}
				if (alert instanceof AbstractNodeToNodeFileOfferUserAlert) {
					continue; // not deleting file offers, because these need a decision
				}
				if (!alert.userCanDismiss()) {
					continue;
				}
				ctx.getAlertManager().dismissAlert(alert.hashCode());
			}
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
		MultiValueTable<String, String> headers = MultiValueTable.from("Location", redirect);
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
