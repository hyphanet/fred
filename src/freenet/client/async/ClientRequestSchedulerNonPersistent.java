/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.LinkedList;

import freenet.node.RequestStarter;
import freenet.support.SortedVectorByNumber;

/**
 * Parallel scheduler structures for non-persistent requests.
 * @author toad
 */
class ClientRequestSchedulerNonPersistent extends ClientRequestSchedulerBase {
	
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	final SortedVectorByNumber[] priorities;
	final LinkedList /* <BaseSendableGet> */ recentSuccesses;
	
	ClientRequestSchedulerNonPersistent(ClientRequestScheduler sched) {
		super(sched.isInsertScheduler, sched.isSSKScheduler, sched.isInsertScheduler ? null : new HashMap(), new HashMap(), new LinkedList());
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		recentSuccesses = new LinkedList();
	}

	boolean persistent() {
		return true;
	}

}
