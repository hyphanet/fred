/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

public class RandomGrabArrayWithClient extends RandomGrabArray implements RemoveRandomWithObject {
    private Object client;

    public RandomGrabArrayWithClient(Object client, boolean persistent, ObjectContainer container,
                                     RemoveRandomParent parent) {
        super(persistent, container, parent);
        this.client = client;
    }

    @Override
    public final Object getObject() {
        return client;
    }

    @Override
    public void setObject(Object client, ObjectContainer container) {
        this.client = client;

        if (persistent) {
            container.store(this);
        }
    }
}
