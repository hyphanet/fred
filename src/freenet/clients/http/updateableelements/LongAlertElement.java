package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;

/** A pushed alert box, provides detailed information */
public class LongAlertElement extends BaseAlertElement {
	public LongAlertElement(ToadletContext ctx) {
		super("div", ctx);
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
			if (!alert.isValid()) continue;
			totalNumber++;
			alertsNode.addChild("a", "name", alert.anchor());
			alertsNode.addChild(manager.renderAlert(alert));
		}
		if (totalNumber == 0) {
			addChild(new HTMLNode("#", ""));
		}
		addChild(alertsNode);
	}

}
