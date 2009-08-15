package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;

/** A pushed element that writes the alerts in XML format*/
public class XmlAlertElement extends BaseAlertElement {

	public XmlAlertElement(ToadletContext ctx) {
		super("div", "style", "display:none;", ctx);
		init();
	}
	
	@Override
	public void updateState(boolean initial) {
		children.clear();

		UserAlertManager manager = ((SimpleToadletServer) ctx.getContainer()).getCore().alerts;

		HTMLNode alertsNode = new HTMLNode("alerts","id","alerts");

		UserAlert[] alerts = manager.getAlerts();
		for (int i = 0; i < alerts.length; i++) {
			UserAlert alert = alerts[i];
			if (!alert.isValid()) continue;
			HTMLNode alertNode = new HTMLNode("alert");
			alertNode.addChild(new HTMLNode("alertTitle", HTMLEncoder.encode(alert.getTitle())));
			alertNode.addChild(new HTMLNode("shortDescription", HTMLEncoder.encode(alert.getShortText())));
			alertNode.addChild(new HTMLNode("priority", String.valueOf(alert.getPriorityClass())));
			alertNode.addChild(new HTMLNode("anchor", HTMLEncoder.encode(alert.anchor())));
			alertNode.addChild(new HTMLNode("canDismiss",String.valueOf(alert.userCanDismiss())));
			alertsNode.addChild(alertNode);
		}

		addChild(alertsNode);
	}

}
