package freenet.node.useralerts;

import java.util.Iterator;
import java.util.Map;

import freenet.clients.fcp.FCPMessage;
import freenet.clients.fcp.FeedMessage;
import freenet.support.HTMLNode;

public abstract class StoringUserEvent<T extends StoringUserEvent<T>> extends AbstractUserEvent {

	protected final Map<String, T> events;

	protected StoringUserEvent(Map<String, T> events) {
		this.events = events;
	}

	protected StoringUserEvent(Type eventType, boolean userCanDismiss, String title, String text,
			String shortText, HTMLNode htmlText, short priorityClass,
			boolean valid, String dismissButtonText,
			boolean shouldUnregisterOnDismiss, Object userIdentifier, Map<String, T> events) {
		super(eventType, userCanDismiss, title, text, shortText, htmlText, priorityClass,
				valid, dismissButtonText, shouldUnregisterOnDismiss,
				userIdentifier);
		this.events = events;
	}

	public abstract String getEventText();
	public abstract HTMLNode getEventHTMLText();

	@Override
	public HTMLNode getHTMLText() {
		HTMLNode text = new HTMLNode("div");
		synchronized(events) {
			for(StoringUserEvent<T> event : events.values()) {
				text.addChild(event.getEventHTMLText());
			}
		}
		return text;
	}

	@Override
	public FCPMessage getFCPMessage() {
		return new FeedMessage(getEventText(), getEventText(), getEventText(), getPriorityClass(), getUpdatedTime());
	}

	@Override
	public void onDismiss() {
		synchronized(events) {
			for(Iterator<T> iter = events.values().iterator(); iter.hasNext();) {
				T event = iter.next();
				event.onEventDismiss();
				iter.remove();
			}
		}
	}

	@Override
	public boolean isValid() {
		boolean valid;
		synchronized(events) {
			valid = !events.isEmpty();
		}
		return valid;
	}

	public abstract void onEventDismiss();

}
