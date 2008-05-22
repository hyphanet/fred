/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.keys.Key;
import freenet.node.SendableGet;

public interface CooldownQueue {

	/**
	 * Add a key to the end of the queue. Returns the time at which it will be valid again.
	 */
	public abstract long add(Key key, SendableGet client);

	/**
	 * Remove a key whose cooldown time has passed.
	 * @return Either a Key or null if no keys have passed their cooldown time.
	 */
	public abstract Key removeKeyBefore(long now);

	/**
	 * @return True if the key was found.
	 */
	public abstract boolean removeKey(Key key, SendableGet client, long time);

}