package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.l10n.NodeL10n;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;

/** A pushed alert box, provides detailed information */
public class LongAlertElement extends BaseAlertElement {
	
	private boolean showOnlyErrors;
	public LongAlertElement(ToadletContext ctx,boolean showOnlyErrors) {
		super("div", ctx);
		this.showOnlyErrors=showOnlyErrors;
		init();
	}

	@Override
	public void updateState(boolean initial) {
		children.clear();

		UserAlertManager manager = ((SimpleToadletServer) ctx.getContainer()).getCore().alerts;

		HTMLNode alertsNode = new HTMLNode("div");
		UserAlert[] alerts = manager.getAlerts();
		int totalNumber = 0;
		for (int i = 0; i < alerts.length; i++) {
			UserAlert alert = alerts[i];
			if(showOnlyErrors && alert.getPriorityClass() > alert.ERROR)
				continue;
			if (!alert.isValid())
				continue;
			totalNumber++;
			alertsNode.addChild("a", "name", alert.anchor());
			alertsNode.addChild(manager.renderAlert(alert));
		}
		if (totalNumber == 0) {
			alertsNode = new HTMLNode("div", "class", "infobox");
			alertsNode.addChild("div", "class", "infobox-content").addChild("div", NodeL10n.getBase().getString("UserAlertsToadlet.noMessages"));
		}
		addChild(alertsNode);
	}

}
