package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContainer;
import freenet.clients.http.ToadletContext;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.node.useralerts.UserEventListener;
import freenet.support.HTMLNode;

public class ShortAlertElement extends BaseUpdateableElement {

	private final UserEventListener listener;
	
	private final ToadletContext ctx;
	
	private boolean drawDumpEventsForm;
	
	private final boolean advancedMode;
	
	private final String title;
	
	public ShortAlertElement(ToadletContext ctx,boolean drawDumpEventsForm,boolean advancedMode,String title){
		super("div",ctx);
		this.ctx=ctx;
		this.drawDumpEventsForm=drawDumpEventsForm;
		this.advancedMode=advancedMode;
		this.title=title;
		init();
		listener=new UserEventListener() {
			public void alertsChanged() {
				((SimpleToadletServer) ShortAlertElement.this.ctx.getContainer()).pushDataManager.updateElement(getId());
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
		return ShortAlertElement.class.getSimpleName();
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.PROGRESSBAR_UPDATER;
	}

	@Override
	public void updateState(boolean initial) {
		children.clear();
		
		UserAlertManager manager=((SimpleToadletServer)ctx.getContainer()).getCore().alerts;
		
		UserAlert[] currentAlerts = manager.getAlerts();
		short maxLevel = Short.MAX_VALUE;
		int events = 0;
		for(int i=0;i<currentAlerts.length;i++) {
			if (!currentAlerts[i].isValid())
				continue;
			short level = currentAlerts[i].getPriorityClass();
			if(level < maxLevel) maxLevel = level;
			if(currentAlerts[i].isEventNotification()) events++;
		}
		if(maxLevel == Short.MAX_VALUE)
			addChild(new HTMLNode("#", ""));
		if(events < 2) drawDumpEventsForm = false;
		HTMLNode boxNode = new HTMLNode("div", "class", "infobox infobox-"+UserAlertManager.getAlertLevelName(maxLevel)+" infobox-summary-status-box");
		boxNode.addChild("div", "class", "infobox-header infobox summary-status-header", title);
		HTMLNode contentNode = boxNode.addChild("div", "class", "infobox-content infobox-summary-status-content");
		if(!advancedMode)
			contentNode.addChild("p", "class", "click-for-more", UserAlertManager.l10n("clickForMore"));
		HTMLNode alertsNode = contentNode.addChild("ul", "class", "alert-summary");
		int totalNumber = 0;
		for (int i = 0; i < currentAlerts.length; i++) {
			UserAlert alert = currentAlerts[i];
			if (!alert.isValid())
				continue;
			HTMLNode listItem = alertsNode.addChild("li", "class", "alert-summary-text-"+UserAlertManager.getAlertLevelName(alert.getPriorityClass()));
			listItem.addChild("a", "href", "/alerts/#"+alert.anchor(), alert.getShortText());
			totalNumber++;
		}
		if(drawDumpEventsForm) {
			HTMLNode dumpFormNode = contentNode.addChild("form", new String[] { "action", "method" }, new String[] { "/", "post" }).addChild("div");
			dumpFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", ctx.getContainer().getFormPassword() });
			StringBuilder sb = new StringBuilder();
			for(int i=0;i<currentAlerts.length;i++) {
				if(!currentAlerts[i].isEventNotification()) continue;
				if(sb.length() != 0)
					sb.append(",");
				sb.append(currentAlerts[i].anchor());
			}
			dumpFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "events", sb.toString() });
			dumpFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "dismiss-events", UserAlertManager.l10n("dumpEventsButton") });
		}
		addChild(boxNode);
	}

}
