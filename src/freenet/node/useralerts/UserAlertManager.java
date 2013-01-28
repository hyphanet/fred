/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.node.fcp.FCPConnectionHandler;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.Logger;

/**
 * Collection of UserAlert's.
 */
public class UserAlertManager implements Comparator<UserAlert> {
	// No point keeping them sorted as some alerts can change priority.
	private final Set<UserAlert> alerts;
	private final NodeClientCore core;
	private final Set<FCPConnectionHandler> subscribers;
	private final Map<UserEvent.Type, UserEvent> events;
	private final Set<UserEvent.Type> unregisteredEventTypes;
	private long lastUpdated;

	public UserAlertManager(NodeClientCore core) {
		this.core = core;
		alerts = new HashSet<UserAlert>();
		subscribers = new CopyOnWriteArraySet<FCPConnectionHandler>();
		events = new HashMap<UserEvent.Type, UserEvent>();
		unregisteredEventTypes = new HashSet<UserEvent.Type>();
		lastUpdated = System.currentTimeMillis();
	}

	public void register(UserAlert alert) {
		if(alert instanceof UserEvent)
			register((UserEvent) alert);
		synchronized (alerts) {
			if (!alerts.contains(alert)) {
				alerts.add(alert);
				lastUpdated = System.currentTimeMillis();
				notifySubscribers(alert);
			}
		}
	}

	public void register(UserEvent event) {
		// The event is ignored if it has been indefinitely unregistered
		synchronized(unregisteredEventTypes) {
			if(unregisteredEventTypes.contains(event.getEventType()))
				return;
		}
		// Only the latest event is displayed as an alert
		synchronized (events) {
			UserEvent lastEvent = events.get(event.getEventType());
			synchronized (alerts) {
				if (lastEvent != null)
					alerts.remove(lastEvent);
				alerts.add(event);
			}
			events.put(event.getEventType(), event);
			lastUpdated = System.currentTimeMillis();
			notifySubscribers(event);
		}
	}

	private void notifySubscribers(final UserAlert alert) {
		// Run off-thread, because of locking, and because client
		// callbacks may take some time
		core.clientContext.mainExecutor.execute(new Runnable() {
			@Override
			public void run() {
				for (FCPConnectionHandler subscriber : subscribers)
					subscriber.outputHandler.queue(alert.getFCPMessage());
			}
		}, "UserAlertManager callback executor");
	}

	public void unregister(UserAlert alert) {
		if(alert == null) return;
		if(alert instanceof UserEvent)
			unregister(((UserEvent)alert).getEventType());
		synchronized (alerts) {
			alerts.remove(alert);
		}
	}

	public void unregister(UserEvent.Type eventType) {
		if(eventType.unregisterIndefinitely())
			synchronized (unregisteredEventTypes) {
				unregisteredEventTypes.add(eventType);
			}
		synchronized (events) {
			UserEvent latestEvent;
			latestEvent = events.remove(eventType);
			if(latestEvent != null)
				synchronized(alerts) {
					alerts.remove(latestEvent);
				}
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
		for (UserAlert userAlert: userAlerts) {
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

	@Override
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

	public HTMLNode createAlerts() {
		return createAlerts(true);
	}

	/**
	 * Write the alerts as HTML.
	 */
	public HTMLNode createAlerts(boolean showOnlyErrors) {
		HTMLNode alertsNode = new HTMLNode("div");
		int totalNumber = 0;
		for (UserAlert alert: getAlerts()) {
			if(showOnlyErrors && alert.getPriorityClass() > UserAlert.ERROR)
				continue;
			if (!alert.isValid())
				continue;
			totalNumber++;
			alertsNode.addChild("a", "name", alert.anchor());
			alertsNode.addChild(renderAlert(alert));
		}
		if (totalNumber == 0) {
			return new HTMLNode("#", "");
		}
		return alertsNode;
	}
	
	/**
	 * Renders the given alert and returns the rendered HTML node.
	 * 
	 * @param userAlert
	 *            The user alert to render
	 * @return The rendered HTML node
	 */
	public HTMLNode renderAlert(UserAlert userAlert) {
		HTMLNode userAlertNode = null;
		short level = userAlert.getPriorityClass();
		userAlertNode = new HTMLNode("div", "class", "infobox infobox-"+getAlertLevelName(level));

		userAlertNode.addChild("div", "class", "infobox-header", userAlert.getTitle());
		HTMLNode alertContentNode = userAlertNode.addChild("div", "class", "infobox-content");
		alertContentNode.addChild(userAlert.getHTMLText());
		if (userAlert.userCanDismiss()) {
			HTMLNode dismissFormNode = alertContentNode.addChild("form", new String[] { "action", "method" }, new String[] { "/alerts/", "post" }).addChild("div");
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

	public HTMLNode createSummary() {
		// This method is called by the toadlets when they want to show
		// a summary of alerts. With a status bar, we only show full errors here.
		return createAlerts(true);
	}
	
	static final HTMLNode ALERTS_LINK = new HTMLNode("a", "href", "/alerts/").setReadOnly();

	/**
	 * Write the alert summary as HTML to a StringBuilder
	 */
	public HTMLNode createSummary(boolean oneLine) {
		short highestLevel = 99;
		int numberOfCriticalError = 0;
		int numberOfError = 0;
		int numberOfWarning = 0;
		int numberOfMinor = 0;
		int totalNumber = 0;
		for (UserAlert alert: getAlerts()) {
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
			return null;

		if (totalNumber == 0)
			return new HTMLNode("#", "");

		boolean separatorNeeded = false;
		String separator = oneLine?", ":" | ";
		int messageTypes=0;
		StringBuilder alertSummaryString = new StringBuilder(1024);
		if (numberOfCriticalError != 0 && !oneLine) {
			alertSummaryString.append(l10n("criticalErrorCountLabel")).append(' ').append(numberOfCriticalError);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfError != 0 && !oneLine) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			alertSummaryString.append(l10n("errorCountLabel")).append(' ').append(numberOfError);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfWarning != 0) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			if(oneLine) {
			alertSummaryString.append(numberOfWarning).append(' ').append(l10n("warningCountLabel").replace(":", ""));
			} else {
				alertSummaryString.append(l10n("warningCountLabel")).append(' ').append(numberOfWarning);
			}
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfMinor != 0) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			if(oneLine) {
				alertSummaryString.append(numberOfMinor).append(' ').append(l10n("minorCountLabel").replace(":", ""));
			} else {
				alertSummaryString.append(l10n("minorCountLabel")).append(' ').append(numberOfMinor);
			}
			separatorNeeded = true;
			messageTypes++;
		}
		if (messageTypes != 1 && !oneLine) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			alertSummaryString.append(l10n("totalLabel")).append(' ').append(totalNumber);
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
		summaryBox.addChild("div", "class", "infobox-header", l10n("alertsTitle"));
		HTMLNode summaryContent = summaryBox.addChild("div", "class", "infobox-content");
		if(!oneLine) {
			summaryContent.addChild("#", alertSummaryString.toString() + separator + " ");
			NodeL10n.getBase().addL10nSubstitution(summaryContent, "UserAlertManager.alertsOnAlertsPage",
				new String[] { "link" }, new HTMLNode[] { ALERTS_LINK });
		} else {
			summaryContent.addChild("a", "href", "/alerts/", NodeL10n.getBase().getString("StatusBar.alerts") + " " + alertSummaryString.toString());
		}
		summaryBox.addAttribute("id", "messages-summary-box");
		return summaryBox;
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("UserAlertManager."+key);
	}

	public void dumpEvents(HashSet<String> toDump) {
		// An iterator might be faster, but we don't want to call methods on the alert within the lock.
		for(UserAlert alert: getAlerts()) {
			if(!alert.isEventNotification()) continue;
			if(!toDump.contains(alert.anchor())) continue;
			unregister(alert);
			alert.onDismiss();
		}
	}

	public void watch(final FCPConnectionHandler subscriber) {
                subscribers.add(subscriber);
		// Run off-thread, because of locking, and because client
		// callbacks may take some time
		core.clientContext.mainExecutor.execute(new Runnable() {
			@Override
			public void run() {
				for (UserAlert alert : getAlerts())
                                        if(alert.isValid())
					    subscriber.outputHandler.queue(alert.getFCPMessage());
			}
		}, "UserAlertManager callback executor");
		subscribers.add(subscriber);
	}

	public void unwatch(FCPConnectionHandler subscriber) {
		subscribers.remove(subscriber);
	}

	//Formats a Unix timestamp according to RFC 3339
	private String formatTime(long time) {
		final Format format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
		String date = format.format(new Date(time));
		//Z doesn't include a colon between the hour and the minutes
		return date.substring(0, 22) + ":" + date.substring(22);
	}

	public String getAtom(String startURI) {
		String messagesURI = startURI + "/alerts/";
		String feedURI = startURI + "/feed/";

		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
		sb.append("<feed xmlns=\"http://www.w3.org/2005/Atom\">\n");
		sb.append("\n");
		sb.append("  <title>").append(l10n("feedTitle")).append("</title>\n");
		sb.append("  <link href=\"").append(feedURI).append("\" rel=\"self\"/>\n");
		sb.append("  <link href=\"").append(startURI).append("\"/>\n");
		sb.append("  <updated>").append(formatTime(lastUpdated)).append("</updated>\n");
		sb.append("  <id>urn:node:").append(Base64.encode(core.node.getDarknetPubKeyHash())).append("</id>\n");
		sb.append("  <logo>").append("/favicon.ico").append("</logo>\n");
		UserAlert[] alerts = getAlerts();
		for(int i = alerts.length - 1; i >= 0; i--) {
			UserAlert alert = alerts[i];
			if (alert.isValid()) {
				sb.append("\n");
				sb.append("  <entry>\n");
				sb.append("    <title>").append(alert.getTitle()).append("</title>\n");
				sb.append("    <link href=\"").append(messagesURI).append("#").append(alert.anchor()).append("\"/>\n");
				sb.append("    <summary>").append(alert.getShortText()).append("</summary>\n");
				sb.append("    <content type=\"text\">").append(alert.getText()).append("</content>\n");
				sb.append("    <id>urn:feed:").append(alert.anchor()).append("</id>\n");
				sb.append("    <updated>").append(formatTime(alert.getUpdatedTime())).append("</updated>\n");
				sb.append("  </entry>\n");
			}
		}
		sb.append("\n</feed>\n");
		return sb.toString();
	}
}
