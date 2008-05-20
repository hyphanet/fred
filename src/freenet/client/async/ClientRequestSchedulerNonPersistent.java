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
class ClientRequestSchedulerNonPersistent {
	
	// These are package-visible so that ClientRequestSchedulerCore can conveniently access them.
	// THEY SHOULD NOT BE ACCESSED DIRECTLY BY ANY OTHER CLASS!
	
	final HashMap allRequestsByClientRequest;
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	final SortedVectorByNumber[] priorities;
	/** All pending gets by key. Used to automatically satisfy pending requests when either the key is fetched by
	 * an overlapping request, or it is fetched by a request from another node. Operations on this are synchronized on
	 * itself. */
	final HashMap /* <Key, SendableGet[]> */ pendingKeys;
	final LinkedList /* <BaseSendableGet> */ recentSuccesses;
	
	ClientRequestSchedulerNonPersistent(ClientRequestScheduler sched) {
		allRequestsByClientRequest = new HashMap();
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		if(!sched.isInsertScheduler)
			pendingKeys = new HashMap();
		else
			pendingKeys = null;
		recentSuccesses = new LinkedList();
	}

}
