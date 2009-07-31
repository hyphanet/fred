package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.node.useralerts.UserEventListener;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;

public class XmlAlertElement extends BaseUpdateableElement {

	private final UserEventListener	listener;

	private final ToadletContext	ctx;

	public XmlAlertElement(ToadletContext ctx) {
		super("div", "style", "display:none;", ctx);
		this.ctx = ctx;
		init();
		listener = new UserEventListener() {
			public void alertsChanged() {
				((SimpleToadletServer) XmlAlertElement.this.ctx.getContainer()).pushDataManager.updateElement(getId());
			}
		};
		((SimpleToadletServer) ctx.getContainer()).getCore().alerts.registerListener(listener);
	}

	@Override
	public void dispose() {
		((SimpleToadletServer) ctx.getContainer()).getCore().alerts.deregisterListener(listener);
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId();
	}

	private static String getId() {
		return XmlAlertElement.class.getSimpleName();
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.PROGRESSBAR_UPDATER;
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
			alertsNode.addChild(alertNode);
		}

		addChild(alertsNode);
	}

}
