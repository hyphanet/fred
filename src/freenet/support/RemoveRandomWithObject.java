/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

public interface RemoveRandomWithObject extends RemoveRandom {
    public Object getObject();

    public boolean isEmpty(ObjectContainer container);

    @Override
    public void removeFrom(ObjectContainer container);

    public void setObject(Object client, ObjectContainer container);
}
