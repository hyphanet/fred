package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.l10n.L10n;
import freenet.l10n.NodeL10n;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;

/** A pushed alerts box */
public class AlertElement extends BaseAlertElement {

	private boolean oneLine;
	
	public AlertElement(ToadletContext ctx){
		this(false,ctx);
	}
	
	// oneLine==true is called by the toadlets when they want to show
	// a summary of alerts. With a status bar, we only show full errors here.
	public AlertElement(boolean oneLine,ToadletContext ctx) {
		super("div", ctx);
		this.oneLine=oneLine;
		init();
	}

	@Override
	public void updateState(boolean initial) {
		children.clear();

		UserAlertManager manager = ((SimpleToadletServer) ctx.getContainer()).getCore().alerts;

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

		if(numberOfMinor == 0 && numberOfWarning == 0 && oneLine)
			return ;

		if (totalNumber == 0)
			addChild(new HTMLNode("#", ""));

		boolean separatorNeeded = false;
		String separator = oneLine?", ":" | ";
		int messageTypes=0;
		StringBuilder alertSummaryString = new StringBuilder(1024);
		if (numberOfCriticalError != 0 && !oneLine) {
			alertSummaryString.append(UserAlertManager.l10n("criticalErrorCountLabel")).append(' ').append(numberOfCriticalError);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfError != 0 && !oneLine) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			alertSummaryString.append(UserAlertManager.l10n("errorCountLabel")).append(' ').append(numberOfError);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfWarning != 0) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			if(oneLine) {
			alertSummaryString.append(numberOfWarning).append(' ').append(UserAlertManager.l10n("warningCountLabel").replace(":", ""));
			} else {
				alertSummaryString.append(UserAlertManager.l10n("warningCountLabel")).append(' ').append(numberOfWarning);
			}
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfMinor != 0) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			if(oneLine) {
				alertSummaryString.append(numberOfMinor).append(' ').append(UserAlertManager.l10n("minorCountLabel").replace(":", ""));
			} else {
				alertSummaryString.append(UserAlertManager.l10n("minorCountLabel")).append(' ').append(numberOfMinor);
			}
			separatorNeeded = true;
			messageTypes++;
		}
		if (messageTypes != 1 && !oneLine) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			alertSummaryString.append(UserAlertManager.l10n("totalLabel")).append(' ').append(totalNumber);
		}
		HTMLNode summaryBox = null;

		String classes = oneLine?"alerts-line contains-":"infobox infobox-";

		if (highestLevel <= UserAlert.CRITICAL_ERROR && !oneLine)
			summaryBox = new HTMLNode("div", "class", classes + "error");
		else if (highestLevel <= UserAlert.ERROR && !oneLine)
			summaryBox = new HTMLNode("div", "class", classes + "alert");
		else if (highestLevel <= UserAlert.WARNING)
			summaryBox = new HTMLNode("div", "class", classes + "warning");
		else if (highestLevel <= UserAlert.MINOR)
			summaryBox = new HTMLNode("div", "class", classes + "information");
		summaryBox.addChild("div", "class", "infobox-header", UserAlertManager.l10n("alertsTitle"));
		HTMLNode summaryContent = summaryBox.addChild("div", "class", "infobox-content");
		if(!oneLine) {
			summaryContent.addChild("#", alertSummaryString.toString() + separator + " ");
			NodeL10n.getBase().addL10nSubstitution(summaryContent, "UserAlertManager.alertsOnAlertsPage",
				new String[] { "link", "/link" },
				new String[] { "<a href=\"/alerts/\">", "</a>" });
		} else {
			summaryContent.addChild("a", "href", "/alerts/", NodeL10n.getBase().getString("StatusBar.alerts") + " " + alertSummaryString.toString());
		}
		summaryBox.addAttribute("id", "messages-summary-box");
		addChild(summaryBox);

	}

}
