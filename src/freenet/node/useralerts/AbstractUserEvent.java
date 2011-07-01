package freenet.node.useralerts;

import freenet.support.HTMLNode;

public class AbstractUserEvent extends AbstractUserAlert implements UserEvent {

	private Type eventType;

	public AbstractUserEvent(Type eventType, boolean userCanDismiss, String title, String text, String shortText, HTMLNode htmlText, short priorityClass, boolean valid, String dismissButtonText, boolean shouldUnregisterOnDismiss, Object userIdentifier) {
		super(userCanDismiss, title, text, shortText, htmlText, priorityClass, valid, dismissButtonText, shouldUnregisterOnDismiss, userIdentifier);
		this.eventType = eventType;
	}

	public AbstractUserEvent() {

	}

	@Override
	public Type getEventType() {
		return eventType;
	}

}
