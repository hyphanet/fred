/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.useralerts;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.HTMLNode;

public class AbstractUserEvent extends AbstractUserAlert implements UserEvent {
    private Type eventType;

    public AbstractUserEvent() {}

    public AbstractUserEvent(Type eventType, boolean userCanDismiss, String title, String text, String shortText,
                             HTMLNode htmlText, short priorityClass, boolean valid, String dismissButtonText,
                             boolean shouldUnregisterOnDismiss, Object userIdentifier) {
        super(userCanDismiss, title, text, shortText, htmlText, priorityClass, valid, dismissButtonText,
              shouldUnregisterOnDismiss, userIdentifier);
        this.eventType = eventType;
    }

    @Override
    public Type getEventType() {
        return eventType;
    }
}
