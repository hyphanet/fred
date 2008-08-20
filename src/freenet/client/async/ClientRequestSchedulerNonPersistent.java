/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.keys.Key;
import freenet.support.Logger;

/**
 * Parallel scheduler structures for non-persistent requests.
 * @author toad
 */
class ClientRequestSchedulerNonPersistent extends ClientRequestSchedulerBase {
	
	private boolean logMINOR;
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	final LinkedList /* <BaseSendableGet> */ recentSuccesses;
	
	/** All pending gets by key. Used to automatically satisfy pending requests when either the key is fetched by
	 * an overlapping request, or it is fetched by a request from another node. Operations on this are synchronized on
	 * itself. */
	protected final Map /* <Key, SendableGet[]> */ pendingKeys;
	
	ClientRequestSchedulerNonPersistent(ClientRequestScheduler sched, boolean forInserts, boolean forSSKs) {
		super(forInserts, forSSKs, new HashMap(), new LinkedList());
		this.sched = sched;
		recentSuccesses = new LinkedList();
		if(forInserts)
			pendingKeys = null;
		else
			pendingKeys = new HashMap();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	boolean persistent() {
		return false;
	}

	ObjectContainer container() {
		return null;
	}

	protected Set makeSetForAllRequestsByClientRequest(ObjectContainer ignored) {
		return new HashSet();
	}
	
	public long countQueuedRequests(ObjectContainer container) {
		if(pendingKeys != null)
			return pendingKeys.size();
		else return 0;
	}
	

}
