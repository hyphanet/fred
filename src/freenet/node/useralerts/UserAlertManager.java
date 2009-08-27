/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;

import freenet.client.async.FeedCallback;
import freenet.clients.http.PageMaker;
import freenet.clients.http.ToadletContext;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.Logger;

/**
 * Collection of UserAlert's.
 */
public class UserAlertManager implements Comparator<UserAlert> {
	private final Set<UserAlert> alerts;
	private final NodeClientCore core;
	private final Set<FeedCallback> subscribers;
	
	/** Listeners that will be notified when the alerts list is changed*/
	private final Set<UserEventListener> listeners;
	private final Map<UserEvent.Type, UserEvent> events;
	private final Set<UserEvent.Type> unregisteredEventTypes;
	private long lastUpdated;

	public UserAlertManager(NodeClientCore core) {
		this.core = core;
		alerts = new TreeSet<UserAlert>(this);
		subscribers = new CopyOnWriteArraySet<FeedCallback>();
		listeners = new CopyOnWriteArraySet<UserEventListener>();
		events = new HashMap<UserEvent.Type, UserEvent>();
		unregisteredEventTypes = new HashSet<UserEvent.Type>();
		lastUpdated = System.currentTimeMillis();
	}

	public void register(UserAlert alert) {
		if(alert instanceof UserEvent)
			register((UserEvent) alert);
		boolean needNotification=false;
		synchronized (alerts) {
			if (!alerts.contains(alert)) {
				alerts.add(alert);
				lastUpdated = System.currentTimeMillis();
				notifySubscribers(alert);
				needNotification=true;
			}
		}
		if(needNotification){
			notifyListeners();
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
			notifyListeners();
		}
	}

	private void notifySubscribers(final UserAlert alert) {
		// Run off-thread, because of locking, and because client
		// callbacks may take some time
		core.clientContext.mainExecutor.execute(new Runnable() {
			public void run() {
				for (FeedCallback subscriber : subscribers)
					subscriber.sendReply(alert.getFCPMessage(subscriber.getIdentifier()));
			}
		}, "UserAlertManager callback executor");
	}
	
	/** Notifies the listeners that alerts are changed*/
	private void notifyListeners(){
		for(UserEventListener l:listeners){
			l.alertsChanged();
		}
	}

	public void unregister(UserAlert alert) {
		if(alert instanceof UserEvent)
			unregister(((UserEvent)alert).getEventType());
		synchronized (alerts) {
			alerts.remove(alert);
		}
		notifyListeners();
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
		notifyListeners();
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
					notifyListeners();
				}
			}
		}
	}
	
	/** Dismisses an alert identified by it's anchor
	 * @param anchor - The anchor that identifies the alert*/
	public boolean dismissByAnchor(String anchor){
		boolean success=false;
		UserAlert[] userAlerts = getAlerts();
		for (int index = 0, count = userAlerts.length; index < count; index++) {
			UserAlert userAlert = userAlerts[index];
			if (userAlert.anchor().compareTo( anchor)==0) {
				if (userAlert.userCanDismiss()) {
					if (userAlert.shouldUnregisterOnDismiss()) {
						userAlert.onDismiss();
						unregister(userAlert);
					} else {
						userAlert.isValid(false);
					}
					notifyListeners();
					success=true;
				}
			}
		}
		return success;
	}

	public UserAlert[] getAlerts() {
		UserAlert[] a;
		synchronized (alerts) {
			a = alerts.toArray(new UserAlert[alerts.size()]);
		}
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

	public HTMLNode createAlerts() {
		return createAlerts(false);
	}

	/**
	 * Write the alerts as HTML.
	 */
	public HTMLNode createAlerts(boolean onlyShowErrors) {
		HTMLNode alertsNode = new HTMLNode("div");
		UserAlert[] alerts = getAlerts();
		int totalNumber = 0;
		for (int i = 0; i < alerts.length; i++) {
			UserAlert alert = alerts[i];
			if(onlyShowErrors && alert.getPriorityClass() > alert.ERROR)
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
	 * Write each alert in uber-concise form as HTML, with a link to 
	 * /alerts/[ anchor pointing to the real alert].
	 */
	public HTMLNode createAlertsShort(String title, boolean advancedMode, boolean drawDumpEventsForm) {
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
		final String additionnalClasses;
		if(advancedMode) {
			additionnalClasses = " infobox-summary-detailed";
		} else additionnalClasses = "";
		HTMLNode boxNode = new HTMLNode("div", "class", "infobox infobox-"+getAlertLevelName(maxLevel)+" infobox-summary-status-box" + additionnalClasses);
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
			listItem.addChild("a", "href", "/alerts/#"+alert.anchor(), alert.getShortText());
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

	public static String getAlertLevelName(short level) {
		if (level <= UserAlert.CRITICAL_ERROR)
			return "error";
		else if (level <= UserAlert.ERROR)
			return "alert";
		else if (level <= UserAlert.WARNING)
			return "warning";
		else if (level <= UserAlert.MINOR)
			return "minor";
		else {
			Logger.error(UserAlertManager.class, "Unknown alert level: "+level, new Exception("debug"));
			return "error";
		}
	}

	public HTMLNode createSummary() {
		if(!PageMaker.THEME.themeFromName(this.core.node.config.get("fproxy").getString("css")).showStatusBar) {
			return createSummary(false);
		} else {
			return createAlerts(true);
		}
	}

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

		if(totalNumber == 0 && oneLine)
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
			alertSummaryString.append(l10n("warningCountLabel")).append(' ').append(numberOfWarning);
			separatorNeeded = true;
			messageTypes++;
		}
		if (numberOfMinor != 0) {
			if (separatorNeeded)
				alertSummaryString.append(separator);
			alertSummaryString.append(l10n("minorCountLabel")).append(' ').append(numberOfMinor);
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
		HTMLNode summaryContent = summaryBox.addChild("div", "class", "infobox-content", alertSummaryString.toString());
		summaryContent.addChild("#", separator);
		L10n.addL10nSubstitution(summaryContent, "UserAlertManager.alertsOnAlertsPage",
				new String[] { "link", "/link" },
				new String[] { "<a href=\"/alerts/\">", "</a>" });
		summaryBox.addAttribute("id", "messages-summary-box");
		return summaryBox;
	}

	public static String l10n(String key) {
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

	public void subscribe(FeedCallback subscriber) {
		subscribers.add(subscriber);
	}

	public void unsubscribe(FeedCallback subscriber) {
		subscribers.remove(subscriber);
	}
	
	/** Registers a listener that will be notified when alerts changed
	 * @param listener - The listener to be registered*/
	public void registerListener(UserEventListener listener){
		listeners.add(listener);
	}
	
	/** Removes a listener
	 * @param listener - The listener to be removed*/
	public void deregisterListener(UserEventListener listener){
		listeners.remove(listener);
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
		sb.append("  <id>urn:node:").append(Base64.encode(core.node.getDarknetIdentity())).append("</id>\n");
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
