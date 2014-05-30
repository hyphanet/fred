/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

/** Interface for requests that require a callback when they go into cooldown. */
public interface WantsCooldownCallback {

    /** The request has gone into cooldown for some period. */
    void enterCooldown(long wakeupTime, ObjectContainer container, ClientContext context);

    /** The request has unexpectedly left cooldown. */
    void clearCooldown(ObjectContainer container);
}
