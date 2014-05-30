/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

public class SectoredRandomGrabArrayWithInt extends SectoredRandomGrabArray implements IntNumberedItem {
    private final int number;

    public SectoredRandomGrabArrayWithInt(int number, boolean persistent, ObjectContainer container,
            RemoveRandomParent parent) {
        super(persistent, container, parent);
        this.number = number;
    }

    @Override
    public int getNumber() {
        return number;
    }

    @Override
    public String toString() {
        return super.toString() + ":" + number;
    }
}
