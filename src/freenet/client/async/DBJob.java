/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

/**
 * A job to be run on the database thread. We will pass a transactional context in,
 * and a ClientContext.
 * @author toad
 */
public interface DBJob {

    /**
     * A database job runs on the database thread. You may only access the database from
     * the database thread.
     * @param container The database. Must be passed around because we can only access it
     * from the database thread. If you switch to another thread, you must schedule a new
     * database job if you want to access the database again at a later point.
     * @param context The client context. Essential but mostly non-persistent objects,
     * including the DBJobRunner with which to schedule more database jobs.
     * @return True if we must commit the transaction immediately e.g. due to it
     * likely using lots of memory. Otherwise we aggregate them based on time. 
     */
    boolean run(ObjectContainer container, ClientContext context);
}
