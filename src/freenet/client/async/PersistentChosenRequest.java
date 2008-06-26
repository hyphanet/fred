/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.SendableRequest;

/**
 * When we choose a request on the database thread, if it is persistent, we store it to the database.
 * The reason for this is that we may crash before it completes, in which case we don't want to lose 
 * it, and the process of choosing a request is destructive i.e. it will be removed from the queue
 * structures before removeFirst() returns. If we do it this way we get the best of all worlds:
 * - We remove the data from the queue, so the request won't be chosen again until it's done, and
 *   choosing requests won't have to go over and ignore a lot of slots.
 * - If we restart, we will restart the persistent requests we were running, and will therefore get
 *   all the relevant callbacks.
 * @author toad
 */
public class PersistentChosenRequest extends ChosenRequest {
	
	ClientRequestSchedulerCore core;
	
	PersistentChosenRequest(ClientRequestSchedulerCore core, SendableRequest req, Object tok, Key key, ClientKey ckey, short prio) {
		super(req, tok, key, ckey, prio);
		this.core = core;
	}
}
