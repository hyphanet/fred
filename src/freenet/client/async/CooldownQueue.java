/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.keys.Key;

import freenet.node.SendableGet;

public interface CooldownQueue {

    /**
     * Add a key to the end of the queue. Returns the time at which it will be valid again.
     */
    public abstract long add(Key key, SendableGet client, ObjectContainer container);

    /**
     * Remove a key whose cooldown time has passed.
     * @param dontCareAfter If the next item to come out of the cooldown
     * queue is more than this many millis after now, return null.
     * @return Either an array of Key's or a Long indicating the time at
     * which the next key will be removed from the cooldown, or null if
     * no keys have passed their cooldown time.
     */
    public abstract Object removeKeyBefore(long now, long dontCareAfter, ObjectContainer container, int maxKeys);

    /**
     * @return True if the key was found.
     */
    public abstract boolean removeKey(Key key, SendableGet client, long time, ObjectContainer container);
}
