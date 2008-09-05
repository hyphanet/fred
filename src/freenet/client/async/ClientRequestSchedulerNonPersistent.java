/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.SendableRequest;
import freenet.support.Logger;

/**
 * Parallel scheduler structures for non-persistent requests.
 * @author toad
 */
class ClientRequestSchedulerNonPersistent extends ClientRequestSchedulerBase {
	
	private boolean logMINOR;
	
	/** All pending gets by key. Used to automatically satisfy pending requests when either the key is fetched by
	 * an overlapping request, or it is fetched by a request from another node. Operations on this are synchronized on
	 * itself. */
	protected final Map /* <Key, SendableGet[]> */ pendingKeys;
	
	protected final List<BaseSendableGet>recentSuccesses;
	
	ClientRequestSchedulerNonPersistent(ClientRequestScheduler sched, boolean forInserts, boolean forSSKs) {
		super(forInserts, forSSKs);
		this.sched = sched;
		recentSuccesses = new LinkedList<BaseSendableGet>();
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

	public void succeeded(BaseSendableGet succeeded, ObjectContainer container) {
		// Do nothing.
		// FIXME: Keep a list of recently succeeded ClientRequester's.
		if(isInsertScheduler) return;
		if(persistent()) {
			container.activate(succeeded, 1);
		}
		if(succeeded.isEmpty(container)) return;
			if(logMINOR)
				Logger.minor(this, "Recording successful fetch from "+succeeded);
			recentSuccesses.add(succeeded);
			while(recentSuccesses.size() > 8)
				recentSuccesses.remove(0);
	}


}
