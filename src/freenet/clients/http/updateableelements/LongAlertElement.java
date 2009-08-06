package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.node.useralerts.UserEventListener;
import freenet.support.HTMLNode;

public class LongAlertElement extends BaseUpdateableElement {

	private final UserEventListener listener;
	
	private final ToadletContext ctx;
	
	public LongAlertElement(ToadletContext ctx){
		super("div",ctx);
		this.ctx=ctx;
		init();
		listener=new UserEventListener() {
			public void alertsChanged() {
				((SimpleToadletServer) LongAlertElement.this.ctx.getContainer()).pushDataManager.updateElement(getId());
			}
		};
		((SimpleToadletServer)ctx.getContainer()).getCore().alerts.registerListener(listener);
	}
	
	@Override
	public void dispose() {
		((SimpleToadletServer)ctx.getContainer()).getCore().alerts.deregisterListener(listener);
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId();
	}
	
	private static String getId(){
		return LongAlertElement.class.getSimpleName();
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.REPLACER_UPDATER;
	}

	@Override
	public void updateState(boolean initial) {
		children.clear();
		
		UserAlertManager manager=((SimpleToadletServer)ctx.getContainer()).getCore().alerts;
		
		HTMLNode alertsNode = new HTMLNode("div");
		UserAlert[] alerts = manager.getAlerts();
		int totalNumber = 0;
		for (int i = 0; i < alerts.length; i++) {
			UserAlert alert = alerts[i];
			if (!alert.isValid())
				continue;
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
