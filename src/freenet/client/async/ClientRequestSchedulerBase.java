/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.RequestStarter;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.SortedVectorByNumber;

/**
 * Base class for ClientRequestSchedulerCore and ClientRequestSchedulerNonPersistent, 
 * contains some of the methods and most of the variables. In particular, it contains all 
 * the methods that deal primarily with pendingKeys.
 * @author toad
 */
abstract class ClientRequestSchedulerBase {
	
	/** Minimum number of retries at which we start to hold it against a request.
	 * See the comments on fixRetryCount; we don't want many untried requests to prevent
	 * us from trying requests which have only been tried once (e.g. USK checkers), from 
	 * other clients (and we DO want retries to take precedence over client round robin IF 
	 * the request has been tried many times already). */
	private static final int MIN_RETRY_COUNT = 3;

	private static boolean logMINOR;
	
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	protected final SortedVectorByNumber[] priorities;
	protected final Map allRequestsByClientRequest;
	protected final List /* <BaseSendableGet> */ recentSuccesses;

	abstract boolean persistent();
	
	protected ClientRequestSchedulerBase(boolean forInserts, boolean forSSKs, Map allRequestsByClientRequest, List recentSuccesses) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.allRequestsByClientRequest = allRequestsByClientRequest;
		this.recentSuccesses = recentSuccesses;
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequestSchedulerBase.class);
	}
	
	void innerRegister(SendableRequest req, RandomSource random, ObjectContainer container) {
		if(isInsertScheduler && req instanceof BaseSendableGet)
			throw new IllegalArgumentException("Adding a SendableGet to an insert scheduler!!");
		if((!isInsertScheduler) && req instanceof SendableInsert)
			throw new IllegalArgumentException("Adding a SendableInsert to a request scheduler!!");
		if(req.persistent() != persistent())
			throw new IllegalArgumentException("innerRegister for persistence="+req.persistent()+" but our persistence is "+persistent());
		if(req.getPriorityClass(container) == 0) {
			Logger.normal(this, "Something wierd...");
			Logger.normal(this, "Priority "+req.getPriorityClass(container));
		}
		int retryCount = req.getRetryCount();
		short prio = req.getPriorityClass(container);
		if(logMINOR) Logger.minor(this, "Still registering "+req+" at prio "+prio+" retry "+retryCount+" for "+req.getClientRequest());
		Set v = (Set) allRequestsByClientRequest.get(req.getClientRequest());
		if(v == null) {
			v = makeSetForAllRequestsByClientRequest(container);
			allRequestsByClientRequest.put(req.getClientRequest(), v);
		}
		v.add(req);
		addToGrabArray(prio, retryCount, fixRetryCount(retryCount), req.getClient(), req.getClientRequest(), req, random, container);
		if(logMINOR) Logger.minor(this, "Registered "+req+" on prioclass="+prio+", retrycount="+retryCount+" v.size()="+v.size());
	}
	
	synchronized void addToGrabArray(short priorityClass, int retryCount, int rc, Object client, ClientRequester cr, SendableRequest req, RandomSource random, ObjectContainer container) {
		if((priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS) || (priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS))
			throw new IllegalStateException("Invalid priority: "+priorityClass+" - range is "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" (most important) to "+RequestStarter.MINIMUM_PRIORITY_CLASS+" (least important)");
		// Priority
		SortedVectorByNumber prio = priorities[priorityClass];
		if(prio == null) {
			prio = new SortedVectorByNumber(persistent());
			priorities[priorityClass] = prio;
			if(persistent())
				container.set(this);
		}
		// Client
		SectoredRandomGrabArrayWithInt clientGrabber = (SectoredRandomGrabArrayWithInt) prio.get(rc, container);
		if(persistent()) container.activate(clientGrabber, 1);
		if(clientGrabber == null) {
			clientGrabber = new SectoredRandomGrabArrayWithInt(rc, persistent(), container);
			prio.add(clientGrabber, container);
			if(logMINOR) Logger.minor(this, "Registering retry count "+rc+" with prioclass "+priorityClass+" on "+clientGrabber+" for "+prio);
		}
		// SectoredRandomGrabArrayWithInt and lower down have hierarchical locking and auto-remove.
		// To avoid a race condition it is essential to mirror that here.
		synchronized(clientGrabber) {
			// Request
			SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) clientGrabber.getGrabber(client);
			if(persistent()) container.activate(requestGrabber, 1);
			if(requestGrabber == null) {
				requestGrabber = new SectoredRandomGrabArrayWithObject(client, persistent(), container);
				if(logMINOR)
					Logger.minor(this, "Creating new grabber: "+requestGrabber+" for "+client+" from "+clientGrabber+" : "+prio+" : prio="+priorityClass+", rc="+rc);
				clientGrabber.addGrabber(client, requestGrabber, container);
			}
			requestGrabber.add(cr, req, container);
		}
	}

	/**
	 * Mangle the retry count.
	 * Below a certain number of attempts, we don't prefer one request to another just because
	 * it's been tried more times. The reason for this is to prevent floods of low-retry-count
	 * requests from starving other clients' requests which need to be retried. The other
	 * solution would be to sort by client before retry count, but that would be excessive 
	 * IMHO; we DO want to avoid rerequesting keys we've tried many times before.
	 */
	protected int fixRetryCount(int retryCount) {
		return Math.max(0, retryCount-MIN_RETRY_COUNT);
	}

	public void reregisterAll(ClientRequester request, RandomSource random, ClientRequestScheduler lock, ObjectContainer container) {
		SendableRequest[] reqs;
		synchronized(lock) {
			Set h = (Set) allRequestsByClientRequest.get(request);
			if(h == null) return;
			reqs = (SendableRequest[]) h.toArray(new SendableRequest[h.size()]);
		}
		
		for(int i=0;i<reqs.length;i++) {
			SendableRequest req = reqs[i];
			// Unregister from the RGA's, but keep the pendingKeys and cooldown queue data.
			req.unregister(container);
			// Then can do innerRegister() (not register()).
			innerRegister(req, random, container);
		}
	}

	public void succeeded(BaseSendableGet succeeded, ObjectContainer container) {
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

	protected void removeFromAllRequestsByClientRequest(SendableRequest req, ClientRequester cr) {
		if(logMINOR)
			Logger.minor(this, "Removing from allRequestsByClientRequest: "+req+ " for "+cr);
			Set v = (Set) allRequestsByClientRequest.get(cr);
			if(v == null) {
				Logger.error(this, "No HashSet registered for "+cr+" for "+req);
			} else {
				boolean removed = v.remove(req);
				if(v.isEmpty())
					allRequestsByClientRequest.remove(cr);
				if(logMINOR) Logger.minor(this, (removed ? "" : "Not ") + "Removed "+req+" from HashSet for "+cr+" which now has "+v.size()+" elements");
			}
	}

	public void addPendingKeys(GotKeyListener getter, ObjectContainer container) {
		if(persistent())
			container.activate(getter, 1);
		Key[] keyTokens = getter.listKeys(container);
		Key prevTok = null;
		for(int i=0;i<keyTokens.length;i++) {
			Key key = keyTokens[i];
			if(i != 0 && prevTok == key || (prevTok != null && key != null && prevTok.equals(key))) {
				Logger.error(this, "Ignoring duplicate token");
				continue;
			}
			if(getter.persistent())
				container.activate(key, 5);
			addPendingKey(key, getter, container);
			if(persistent())
				container.deactivate(key, 5);
		}
	}
	
	public short getKeyPrio(Key key, short priority, ObjectContainer container) {
		GotKeyListener[] getters = getClientsForPendingKey(key, container);
		if(getters == null) return priority;
		for(int i=0;i<getters.length;i++) {
			if(persistent())
				container.activate(getters[i], 1);
			short prio = getters[i].getPriorityClass(container);
			if(prio < priority) priority = prio;
			if(persistent())
				container.deactivate(getters[i], 1);
		}
		return priority;
	}
	
	public abstract long countQueuedRequests(ObjectContainer container);
	
	protected abstract boolean inPendingKeys(GotKeyListener req, Key key, ObjectContainer container);
	
	public abstract GotKeyListener[] getClientsForPendingKey(Key key, ObjectContainer container);
	
	public abstract boolean anyWantKey(Key key, ObjectContainer container);
	
	public abstract GotKeyListener[] removePendingKey(Key key, ObjectContainer container);
	
	public abstract boolean removePendingKey(GotKeyListener getter, boolean complain, Key key, ObjectContainer container);
	
	abstract void addPendingKey(Key key, GotKeyListener getter, ObjectContainer container);
	
	protected abstract Set makeSetForAllRequestsByClientRequest(ObjectContainer container);

}
