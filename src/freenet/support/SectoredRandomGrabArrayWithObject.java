/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

public class SectoredRandomGrabArrayWithObject extends SectoredRandomGrabArray implements RemoveRandomWithObject {
    private Object object;

    public SectoredRandomGrabArrayWithObject(Object object, boolean persistent, ObjectContainer container,
            RemoveRandomParent parent) {
        super(persistent, container, parent);
        this.object = object;
    }

    @Override
    public Object getObject() {
        return object;
    }

    @Override
    public String toString() {
        return super.toString() + ":" + object;
    }

    @Override
    public void setObject(Object client, ObjectContainer container) {
        object = client;

        if (persistent) {
            container.store(this);
        }
    }
}
