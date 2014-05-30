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

public interface RemoveRandom extends HasCooldownCacheItem {

    /**
     * Return a random RandomGrabArrayItem, or a time at which there will be one, or null
     * if the RGA is empty and should be removed by the parent. 
     */
    public RemoveRandomReturn removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container,
            ClientContext context, long now);

    /** Just for consistency checking */
    public boolean persistent();

    public void removeFrom(ObjectContainer container);

    /**
     * @param existingGrabber
     * @param container
     * @param canCommit If true, can commit to limit memory footprint.
     */
    public void moveElementsTo(RemoveRandom existingGrabber, ObjectContainer container, boolean canCommit);

    public void setParent(RemoveRandomParent newTopLevel, ObjectContainer container);

    // OPTIMISE: Would a stack-trace-less exception be faster?

    /** Either a RandomGrabArrayItem or the time at which we should try again. */
    public static final class RemoveRandomReturn {
        public final RandomGrabArrayItem item;
        public final long wakeupTime;

        RemoveRandomReturn(long wakeupTime) {
            this.item = null;
            this.wakeupTime = wakeupTime;
        }

        RemoveRandomReturn(RandomGrabArrayItem item) {
            this.item = item;
            this.wakeupTime = -1;
        }
    }
}
