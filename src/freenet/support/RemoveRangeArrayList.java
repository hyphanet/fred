/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- JDK imports ------------------------------------------------------------

import java.util.ArrayList;

public class RemoveRangeArrayList<T> extends ArrayList<T> {
    private static final long serialVersionUID = -1L;

    public RemoveRangeArrayList(int capacity) {
        super(capacity);
    }

    @Override
    public void removeRange(int fromIndex, int toIndex) {
        super.removeRange(fromIndex, toIndex);
    }
}
