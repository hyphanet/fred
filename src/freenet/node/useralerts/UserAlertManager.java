package freenet.node.useralerts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import freenet.support.HTMLNode;

/**
 * Collection of UserAlert's.
 */
public class UserAlertManager implements Comparator {

	private final HashSet alerts;

	public UserAlertManager() {
		alerts = new HashSet();
	}

	public void register(UserAlert alert) {
		synchronized (alerts) {
			alerts.add(alert);
		}
	}

	public void unregister(UserAlert alert) {
		synchronized (alerts) {
			alerts.remove(alert);
		}
	}

	public UserAlert[] getAlerts() {
		UserAlert[] a = null;
		synchronized (alerts) {
			a = (UserAlert[]) alerts.toArray(new UserAlert[alerts.size()]);
		}
		Arrays.sort(a, this);
		return a;
	}

	public int compare(Object arg0, Object arg1) {
		UserAlert a0 = (UserAlert) arg0;
		UserAlert a1 = (UserAlert) arg1;
		return a0.getPriorityClass() - a1.getPriorityClass();
	}
	
	/**
	 * Write the alerts as HTML to a StringBuffer
	 */
	public HTMLNode createAlerts() {
		HTMLNode alertsNode = new HTMLNode("div");
		UserAlert[] alerts = getAlerts();
		for (int i = 0; i < alerts.length; i++) {
			UserAlert alert = alerts[i];
			if (!alert.isValid())
				continue;

			HTMLNode alertNode = null;
			short level = alert.getPriorityClass();
			if (level <= UserAlert.CRITICAL_ERROR)
				alertNode = new HTMLNode("div", "class", "infobox infobox-error");
			else if (level <= UserAlert.ERROR)
				alertNode = new HTMLNode("div", "class", "infobox infobox-alert");
			else if (level <= UserAlert.WARNING)
				alertNode = new HTMLNode("div", "class", "infobox infobox-warning");
			else if (level <= UserAlert.MINOR)
				alertNode = new HTMLNode("div", "class", "infobox infobox-information");

			alertsNode.addChild(alertNode);
			alertNode.addChild("div", "class", "infobox-header", alert.getTitle());
			HTMLNode alertContentNode = alertNode.addChild("div", "class", "infobox-content");
			alertContentNode.addChild(alert.getHTMLText());
			if (alert.userCanDismiss()) {
				HTMLNode dismissFormNode = alertContentNode.addChild("form", new String[] { "action", "method" }, new String[] { ".", "post" });
				dismissFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "disable", String.valueOf(alert.hashCode()) });
				dismissFormNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", alert.dismissButtonText() });
			}
		}
		return alertsNode;
	}

	/**
	 * Write the alert summary as HTML to a StringBuffer
	 */
	public HTMLNode createSummary() {
		short highestLevel = 99;
		int numberOfCriticalError = 0;
		int numberOfError = 0;
		int numberOfWarning = 0;
		int numberOfMinor = 0;
		int totalNumber = 0;
		UserAlert[] alerts = getAlerts();
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
		
		if (totalNumber == 0)
			return new HTMLNode("#", "");
		
		boolean separatorNeeded = false;
		StringBuffer alertSummaryString = new StringBuffer(1024);
		if (numberOfCriticalError != 0) {
			alertSummaryString.append("Critical Error: ").append(numberOfCriticalError);
			separatorNeeded = true;
		}
		if (numberOfError != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append("Error: ").append(numberOfError);
			separatorNeeded = true;
		}
		if (numberOfWarning != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append("Warning: ").append(numberOfWarning);
			separatorNeeded = true;
		}
		if (numberOfMinor != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append("Minor: ").append(numberOfMinor);
			separatorNeeded = true;
		}
		if (separatorNeeded)
			alertSummaryString.append(" | ");
		alertSummaryString.append("Total: ").append(totalNumber);

		HTMLNode summaryBox = null;

		if (highestLevel <= UserAlert.CRITICAL_ERROR)
			summaryBox = new HTMLNode("div", "class", "infobox infobox-error");
		else if (highestLevel <= UserAlert.ERROR)
			summaryBox = new HTMLNode("div", "class", "infobox infobox-alert");
		else if (highestLevel <= UserAlert.WARNING)
			summaryBox = new HTMLNode("div", "class", "infobox infobox-warning");
		else if (highestLevel <= UserAlert.MINOR)
			summaryBox = new HTMLNode("div", "class", "infobox infobox-information");
		summaryBox.addChild("div", "class", "infobox-header", "Outstanding alerts");
		HTMLNode summaryContent = summaryBox.addChild("div", "class", "infobox-content", alertSummaryString.toString());
		summaryContent.addChild("#", " | See them on ");
		summaryContent.addChild("a", "href", "/", "the Freenet FProxy Homepage");
		summaryContent.addChild("#", ".");
		return summaryBox;
	}

}
