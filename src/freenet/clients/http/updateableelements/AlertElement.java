package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.l10n.L10n;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.node.useralerts.UserEventListener;
import freenet.support.HTMLNode;

public class AlertElement extends BaseUpdateableElement {

	private final UserEventListener listener;
	
	private final ToadletContext ctx;
	
	public AlertElement(ToadletContext ctx){
		super("div",ctx);
		this.ctx=ctx;
		init();
		listener=new UserEventListener() {
			public void alertsChanged() {
				((SimpleToadletServer) AlertElement.this.ctx.getContainer()).pushDataManager.updateElement(getId());
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
		return AlertElement.class.getSimpleName();
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.REPLACER_UPDATER;
	}

	@Override
	public void updateState(boolean initial) {
		children.clear();
		
		UserAlertManager manager=((SimpleToadletServer)ctx.getContainer()).getCore().alerts;
		
		short highestLevel = 99;
		int numberOfCriticalError = 0;
		int numberOfError = 0;
		int numberOfWarning = 0;
		int numberOfMinor = 0;
		int totalNumber = 0;
		UserAlert[] alerts = manager.getAlerts();
		for (int i = 0; i < alerts.length; i++) {
			UserAlert alert = alerts[i];
			if (!alert.isValid())
				continue;
			short level = alert.getPriorityClass();
			if (level < highestLevel)
				highestLevel = level;
			if (level <= UserAlert.CRITICAL_ERROR)
				numberOfCriticalError++;
			else if (level <= UserAlert.ERROR)
				numberOfError++;
			else if (level <= UserAlert.WARNING)
				numberOfWarning++;
			else if (level <= UserAlert.MINOR)
				numberOfMinor++;
			totalNumber++;
		}
		
		if (totalNumber == 0){
			addChild(new HTMLNode("div"));
			return;
		}
		
		boolean separatorNeeded = false;
		int messageTypes=0;
		StringBuilder alertSummaryString = new StringBuilder(1024);
		if (numberOfCriticalError != 0) {
			alertSummaryString.append(UserAlertManager.l10n("criticalErrorCountLabel")).append(' ').append(numberOfCriticalError);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfError != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append(UserAlertManager.l10n("errorCountLabel")).append(' ').append(numberOfError);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfWarning != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append(UserAlertManager.l10n("warningCountLabel")).append(' ').append(numberOfWarning);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfMinor != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append(UserAlertManager.l10n("minorCountLabel")).append(' ').append(numberOfMinor);
			separatorNeeded = true;
			messageTypes++;
		}
		if (messageTypes != 1) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append(UserAlertManager.l10n("totalLabel")).append(' ').append(totalNumber);
		}
		HTMLNode summaryBox = null;

		if (highestLevel <= UserAlert.CRITICAL_ERROR)
			summaryBox = new HTMLNode("div", "class", "infobox infobox-error");
		else if (highestLevel <= UserAlert.ERROR)
			summaryBox = new HTMLNode("div", "class", "infobox infobox-alert");
		else if (highestLevel <= UserAlert.WARNING)
			summaryBox = new HTMLNode("div", "class", "infobox infobox-warning");
		else if (highestLevel <= UserAlert.MINOR)
			summaryBox = new HTMLNode("div", "class", "infobox infobox-information");
		summaryBox.addChild("div", "class", "infobox-header", UserAlertManager.l10n("alertsTitle"));
		HTMLNode summaryContent = summaryBox.addChild("div", "class", "infobox-content", alertSummaryString.toString());
		summaryContent.addChild("#", " ");
		L10n.addL10nSubstitution(summaryContent, "UserAlertManager.alertsOnAlertsPage",
				new String[] { "link", "/link" },
				new String[] { "<a href=\"/alerts/\">", "</a>" });
		addChild(summaryBox);
	}

}
