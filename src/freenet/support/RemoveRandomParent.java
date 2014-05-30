/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.HasCooldownCacheItem;

public interface RemoveRandomParent extends HasCooldownCacheItem {

    /**
     * If the specified RemoveRandom is empty, remove it.
     * LOCKING: Must be called with no locks held, particularly no locks on the
     * RemoveRandom, because we take locks in order!
     * @param context
     */
    public void maybeRemove(RemoveRandom r, ObjectContainer container, ClientContext context);
}
