/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;

import freenet.clients.http.LinkFixer;
import freenet.clients.http.ToadletContext;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;

/**
 * Collection of UserAlert's.
 */
public class UserAlertManager implements Comparator<UserAlert> {
	private final HashSet<UserAlert> alerts;
	private final NodeClientCore core;

	public UserAlertManager(NodeClientCore core) {
		this.core = core;
		alerts = new LinkedHashSet<UserAlert>();
	}

	public void register(UserAlert alert) {
		synchronized (alerts) {
			if(!alerts.contains(alert))
				alerts.add(alert);
		}
	}

	public void unregister(UserAlert alert) {
		synchronized (alerts) {
			alerts.remove(alert);
		}
	}

	/**
	 * Tries to find the user alert with the given hash code and dismisses it,
	 * if found.
	 * 
	 * @see #unregister(UserAlert)
	 * @param alertHashCode
	 *            The hash code of the user alert to dismiss
	 */
	public void dismissAlert(int alertHashCode) {
		UserAlert[] userAlerts = getAlerts();
		for (int index = 0, count = userAlerts.length; index < count; index++) {
			UserAlert userAlert = userAlerts[index];
			if (userAlert.hashCode() == alertHashCode) {
				if (userAlert.userCanDismiss()) {
					if (userAlert.shouldUnregisterOnDismiss()) {
						userAlert.onDismiss();
						unregister(userAlert);
					} else {
						userAlert.isValid(false);
					}
				}
			}
		}
	}

	public UserAlert[] getAlerts() {
		UserAlert[] a;
		synchronized (alerts) {
			a = alerts.toArray(new UserAlert[alerts.size()]);
		}
		Arrays.sort(a, this);
		return a;
	}

	public int compare(UserAlert a0, UserAlert a1) {
		if(a0 == a1) return 0; // common case, also we should be consistent with == even with proxyuseralert's
		short prio0 = a0.getPriorityClass();
		short prio1 = a1.getPriorityClass();
		if(prio0 - prio1 == 0) {
			boolean isEvent0 = a0.isEventNotification();
			boolean isEvent1 = a1.isEventNotification();
			if(isEvent0 && !isEvent1) return 1;
			if((!isEvent0) && isEvent1) return -1;
			// First go by class
			int classHash0 = a0.getClass().hashCode();
			int classHash1 = a1.getClass().hashCode();
			if(classHash0 > classHash1) return 1;
			else if(classHash0 < classHash1) return -1;
			// Then by object hashCode
			int hash0 = a0.hashCode();
			int hash1 = a1.hashCode();
			if(hash0 > hash1) return 1;
			if(hash1 > hash0) return -1;
			return 0;
		} else {
			if(prio0 > prio1) return 1;
			else return -1;
		}
	}
	
	/**
	 * Write the alerts as HTML.
	 */
	public HTMLNode createAlerts(LinkFixer fixer) {
		HTMLNode alertsNode = new HTMLNode("div");
		UserAlert[] alerts = getAlerts();
		int totalNumber = 0;
		for (int i = 0; i < alerts.length; i++) {
			UserAlert alert = alerts[i];
			if (!alert.isValid())
				continue;
			totalNumber++;
			alertsNode.addChild("a", "name", alert.anchor());
			alertsNode.addChild(renderAlert(alert, fixer));
		}
		if (totalNumber == 0) {
			return new HTMLNode("#", "");
		}
		return alertsNode;
	}

	/**
	 * Write each alert in uber-concise form as HTML, with a link to 
	 * /alerts/[ anchor pointing to the real alert].
	 */
	public HTMLNode createAlertsShort(String title, boolean advancedMode, boolean drawDumpEventsForm, LinkFixer ctx) {
		UserAlert[] currentAlerts = getAlerts();
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
			return new HTMLNode("#", "");
		if(events < 2) drawDumpEventsForm = false;
		HTMLNode boxNode = new HTMLNode("div", "class", "infobox infobox-"+getAlertLevelName(maxLevel)+" infobox-summary-status-box");
		boxNode.addChild("div", "class", "infobox-header infobox summary-status-header", title);
		HTMLNode contentNode = boxNode.addChild("div", "class", "infobox-content infobox-summary-status-content");
		if(!advancedMode)
			contentNode.addChild("p", "class", "click-for-more", l10n("clickForMore"));
		HTMLNode alertsNode = contentNode.addChild("ul", "class", "alert-summary");
		int totalNumber = 0;
		for (int i = 0; i < currentAlerts.length; i++) {
			UserAlert alert = currentAlerts[i];
			if (!alert.isValid())
				continue;
			HTMLNode listItem = alertsNode.addChild("li", "class", "alert-summary-text-"+getAlertLevelName(alert.getPriorityClass()));
			listItem.addChild("a", "href", ctx.fixLink("/alerts/#"+alert.anchor()), alert.getShortText());
			totalNumber++;
		}
		if(drawDumpEventsForm) {
			HTMLNode dumpFormNode = contentNode.addChild("form", new String[] { "action", "method" }, new String[] { "/", "post" }).addChild("div");
			dumpFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
			StringBuilder sb = new StringBuilder();
			for(int i=0;i<currentAlerts.length;i++) {
				if(!currentAlerts[i].isEventNotification()) continue;
				if(sb.length() != 0)
					sb.append(",");
				sb.append(currentAlerts[i].anchor());
			}
			dumpFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "events", sb.toString() });
			dumpFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "dismiss-events", l10n("dumpEventsButton") });
		}
		return boxNode;
	}
	
	/**
	 * Renders the given alert and returns the rendered HTML node.
	 * 
	 * @param userAlert
	 *            The user alert to render
	 * @return The rendered HTML node
	 */
	public HTMLNode renderAlert(UserAlert userAlert, LinkFixer fixer) {
		HTMLNode userAlertNode = null;
		short level = userAlert.getPriorityClass();
		userAlertNode = new HTMLNode("div", "class", "infobox infobox-"+getAlertLevelName(level));

		userAlertNode.addChild("div", "class", "infobox-header", userAlert.getTitle());
		HTMLNode alertContentNode = userAlertNode.addChild("div", "class", "infobox-content");
		alertContentNode.addChild(userAlert.getHTMLText(fixer));
		if (userAlert.userCanDismiss()) {
			HTMLNode dismissFormNode = alertContentNode.addChild("form", new String[] { "action", "method" }, new String[] { "/", "post" }).addChild("div");
			dismissFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "disable", String.valueOf(userAlert.hashCode()) });
			dismissFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
			dismissFormNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "dismiss-user-alert", userAlert.dismissButtonText() });
		}
		return userAlertNode;
	}

	private String getAlertLevelName(short level) {
		if (level <= UserAlert.CRITICAL_ERROR)
			return "error";
		else if (level <= UserAlert.ERROR)
			return "alert";
		else if (level <= UserAlert.WARNING)
			return "warning";
		else if (level <= UserAlert.MINOR)
			return "minor";
		else {
			Logger.error(this, "Unknown alert level: "+level, new Exception("debug"));
			return "error";
		}
	}

	/**
	 * Write the alert summary as HTML to a StringBuilder
	 */
	public HTMLNode createSummary(LinkFixer fixer) {
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
		int messageTypes=0;
		StringBuilder alertSummaryString = new StringBuilder(1024);
		if (numberOfCriticalError != 0) {
			alertSummaryString.append(l10n("criticalErrorCountLabel")).append(' ').append(numberOfCriticalError);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfError != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append(l10n("errorCountLabel")).append(' ').append(numberOfError);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfWarning != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append(l10n("warningCountLabel")).append(' ').append(numberOfWarning);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfMinor != 0) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append(l10n("minorCountLabel")).append(' ').append(numberOfMinor);
			separatorNeeded = true;
			messageTypes++;
		}
		if (messageTypes != 1) {
			if (separatorNeeded)
				alertSummaryString.append(" | ");
			alertSummaryString.append(l10n("totalLabel")).append(' ').append(totalNumber);
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
		summaryBox.addChild("div", "class", "infobox-header", l10n("alertsTitle"));
		HTMLNode summaryContent = summaryBox.addChild("div", "class", "infobox-content", alertSummaryString.toString());
		summaryContent.addChild("#", " ");
		L10n.addL10nSubstitution(summaryContent, "UserAlertManager.alertsOnAlertsPage",
				new String[] { "link", "/link" },
				new String[] { "<a href=\""+fixer.fixLink("/alerts/")+"\">", "</a>" });
		return summaryBox;
	}

	private String l10n(String key) {
		return L10n.getString("UserAlertManager."+key);
	}

	public void dumpEvents(HashSet<String> toDump) {
		// An iterator might be faster, but we don't want to call methods on the alert within the lock.
		UserAlert[] alerts = getAlerts();
		for(int i=0;i<alerts.length;i++) {
			if(!alerts[i].isEventNotification()) continue;
			if(!toDump.contains(alerts[i].anchor())) continue;
			unregister(alerts[i]);
			alerts[i].onDismiss();
		}
	}

}
