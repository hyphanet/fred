/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.keys.ClientKey;
import freenet.support.RandomGrabArray;

public interface RequestScheduler {

	public SendableRequest removeFirst();

	/** Tell the scheduler that a request from a specific RandomGrabArray succeeded.
	 * Definition of "succeeded" will vary, but the point is most schedulers will run another
	 * request from the parentGrabArray in the near future on the theory that if one works,
	 * another may also work. 
	 * */
	public void succeeded(RandomGrabArray parentGrabArray);

	/**
	 * After a key has been requested a few times, it is added to the cooldown queue for
	 * a fixed period, since it would be pointless to rerequest it (especially given ULPRs).
	 * Note that while a key is on the cooldown queue its requestors will still be told if
	 * it is found by ULPRs or back door coalescing.
	 * @param key The key to be added.
	 * @return The time at which the key will leave the cooldown queue.
	 */
	public long queueCooldown(ClientKey key);

	/**
	 * Remove keys from the cooldown queue who have now served their time and can be requested 
	 * again.
	 */
	public void moveKeysFromCooldownQueue();
	
}
