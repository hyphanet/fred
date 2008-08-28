/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.node.BaseSendableGet;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
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
	protected transient ClientRequestScheduler sched;
	/** Transient even for persistent scheduler. */
	protected transient Set<KeyListener> keyListeners;

	abstract boolean persistent();
	
	protected ClientRequestSchedulerBase(boolean forInserts, boolean forSSKs, Map allRequestsByClientRequest, List recentSuccesses) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.allRequestsByClientRequest = allRequestsByClientRequest;
		this.recentSuccesses = recentSuccesses;
		keyListeners = new HashSet<KeyListener>();
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
		addToRequestsByClientRequest(req.getClientRequest(), req, container);
		addToGrabArray(prio, retryCount, fixRetryCount(retryCount), req.getClient(), req.getClientRequest(), req, random, container);
		if(logMINOR) Logger.minor(this, "Registered "+req+" on prioclass="+prio+", retrycount="+retryCount);
		if(persistent())
			sched.maybeAddToStarterQueue(req, container);
	}
	
	protected void addToRequestsByClientRequest(ClientRequester clientRequest, SendableRequest req, ObjectContainer container) {
		synchronized(sched) {
		Set v = (Set) allRequestsByClientRequest.get(req.getClientRequest());
		if(persistent())
			container.activate(v, 1);
		if(v == null) {
			v = makeSetForAllRequestsByClientRequest(container);
			allRequestsByClientRequest.put(req.getClientRequest(), v);
		}
		v.add(req);
		int vSize = v.size();
		if(persistent())
			container.deactivate(v, 1);
		if(logMINOR)
			Logger.minor(this, "Added to allRequestsByClientRequest for "+clientRequest+" size now "+v.size());
		}
	}

	synchronized void addToGrabArray(short priorityClass, int retryCount, int rc, RequestClient client, ClientRequester cr, SendableRequest req, RandomSource random, ObjectContainer container) {
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

	public void reregisterAll(ClientRequester request, RandomSource random, RequestScheduler lock, ObjectContainer container, ClientContext context) {
		SendableRequest[] reqs = getSendableRequests(request, container);
		if(reqs == null) return;
		for(int i=0;i<reqs.length;i++) {
			SendableRequest req = reqs[i];
			if(persistent())
				container.activate(req, 1);
			// Unregister from the RGA's, but keep the pendingKeys and cooldown queue data.
			req.unregister(container, context);
			// Then can do innerRegister() (not register()).
			innerRegister(req, random, container);
			if(persistent())
				container.deactivate(req, 1);
		}
	}

	private SendableRequest[] getSendableRequests(ClientRequester request, ObjectContainer container) {
		synchronized(sched) {
			Set h = (Set) allRequestsByClientRequest.get(request);
			if(h == null) return null;
			if(persistent())
				container.activate(h, 1);
			SendableRequest[] reqs = (SendableRequest[]) h.toArray(new SendableRequest[h.size()]);
			if(persistent())
				container.deactivate(h, 1);
			return reqs;
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

	protected void removeFromAllRequestsByClientRequest(SendableRequest req, ClientRequester cr, boolean dontComplain, ObjectContainer container) {
		synchronized(sched) {
		if(logMINOR)
			Logger.minor(this, "Removing from allRequestsByClientRequest: "+req+ " for "+cr);
			Set v = (Set) allRequestsByClientRequest.get(cr);
			if(v == null) {
				if(!dontComplain)
					Logger.error(this, "No HashSet registered for "+cr+" for "+req);
			} else {
				if(persistent())
					container.activate(v, 1);
				boolean removed = v.remove(req);
				int vSize = v.size();
				if(v.isEmpty())
					allRequestsByClientRequest.remove(cr);
				else {
					if(persistent())
						container.deactivate(v, 1);
				}
				if(logMINOR) Logger.minor(this, (removed ? "" : "Not ") + "Removed "+req+" from HashSet for "+cr+" which now has "+vSize+" elements");
			}
		}
	}

	public synchronized void addPendingKeys(KeyListener listener) {
		keyListeners.add(listener);
		System.out.println("Added pending keys to "+this+" : size now "+keyListeners.size()+" : "+listener);
		Logger.normal(this, "Added pending keys to "+this+" : size now "+keyListeners.size()+" : "+listener);
	}
	
	public synchronized boolean removePendingKeys(KeyListener listener) {
		boolean ret = keyListeners.remove(listener);
		listener.onRemove();
		System.out.println("Removed pending keys from "+this+" : size now "+keyListeners.size()+" : "+listener);
		Logger.normal(this, "Removed pending keys from "+this+" : size now "+keyListeners.size()+" : "+listener);
		return ret;
	}
	
	public synchronized boolean removePendingKeys(HasKeyListener hasListener) {
		boolean found = false;
		for(Iterator<KeyListener> i = keyListeners.iterator();i.hasNext();) {
			KeyListener listener = i.next();
			if(listener.getHasKeyListener() == hasListener) {
				found = true;
				i.remove();
				listener.onRemove();
				System.out.println("Removed pending keys from "+this+" : size now "+keyListeners.size()+" : "+listener);
				Logger.normal(this, "Removed pending keys from "+this+" : size now "+keyListeners.size()+" : "+listener);
			}
		}
		return found;
	}
	
	public short getKeyPrio(Key key, short priority, ObjectContainer container, ClientContext context) {
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		ArrayList<KeyListener> matches = null;
		synchronized(this) {
			for(KeyListener listener : keyListeners) {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				matches.add(listener);
			}
		}
		if(matches == null) return priority;
		for(KeyListener listener : matches) {
			short prio = listener.definitelyWantKey(key, saltedKey, container, sched.clientContext);
			if(prio == -1) continue;
			if(prio < priority) priority = prio;
		}
		return priority;
	}
	
	public synchronized long countQueuedRequests(ObjectContainer container) {
		long count = 0;
		for(KeyListener listener : keyListeners)
			count += listener.countKeys();
		return count;
	}
	
	public boolean anyWantKey(Key key, ObjectContainer container, ClientContext context) {
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		ArrayList<KeyListener> matches = null;
		synchronized(this) {
			for(KeyListener listener : keyListeners) {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				matches.add(listener);
			}
		}
		if(matches != null) {
			for(KeyListener listener : matches) {
				if(listener.definitelyWantKey(key, saltedKey, container, sched.clientContext) >= 0)
					return true;
			}
		}
		return false;
	}
	
	public synchronized boolean anyProbablyWantKey(Key key, ClientContext context) {
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		for(KeyListener listener : keyListeners) {
			if(listener.probablyWantKey(key, saltedKey))
				return true;
		}
		return false;
	}
	
	private long persistentTruePositives;
	private long persistentFalsePositives;
	private long persistentNegatives;
	
	public boolean tripPendingKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		ArrayList<KeyListener> matches = null;
		synchronized(this) {
			for(KeyListener listener : keyListeners) {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				matches.add(listener);
			}
		}
		boolean ret = false;
		if(matches != null) {
			for(KeyListener listener : matches) {
				if(listener.handleBlock(key, saltedKey, block, container, context))
					ret = true;
				if(listener.isEmpty()) {
					synchronized(this) {
						keyListeners.remove(listener);
					}
					listener.onRemove();
				}
			}
		} else return false;
		if(ret) {
			// True positive
			synchronized(this) {
				persistentTruePositives++;
				logFalsePositives("hit");
			}
		} else {
			synchronized(this) {
				persistentFalsePositives++;
				logFalsePositives("false");
			}
		}
		return ret;
	}
	
	synchronized void countNegative() {
		persistentNegatives++;
		if(persistentNegatives % 32 == 0)
			logFalsePositives("neg");
	}
	
	private synchronized void logFalsePositives(String phase) {
		long totalPositives = persistentFalsePositives + persistentTruePositives;
		double percent;
		if(totalPositives > 0)
			percent = ((double) persistentFalsePositives) / totalPositives;
		else
			percent = 0;
		if(!(percent > 2 || logMINOR)) return;
		StringBuilder buf = new StringBuilder();
		if(persistent())
			buf.append("Persistent ");
		else
			buf.append("Transient ");
		buf.append("false positives ");
		buf.append(phase);
		buf.append(": ");
		
		if(totalPositives != 0) {
			buf.append(percent);
			buf.append("% ");
		}
		buf.append("(false=");
		buf.append(persistentFalsePositives);
		buf.append(" true=");
		buf.append(persistentTruePositives);
		buf.append(" negatives=");
		buf.append(persistentNegatives);
		buf.append(')');
		if(percent > 10)
			Logger.error(this, buf.toString());
		else if(percent > 2)
			Logger.normal(this, buf.toString());
		else
			Logger.minor(this, buf.toString());
	}

	public SendableGet[] requestsForKey(Key key, ObjectContainer container, ClientContext context) {
		ArrayList<SendableGet> list = null;
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		synchronized(this) {
		for(KeyListener listener : keyListeners) {
			if(!listener.probablyWantKey(key, saltedKey)) continue;
			SendableGet[] reqs = listener.getRequestsForKey(key, saltedKey, container, context);
			if(reqs == null) continue;
			if(list == null) list = new ArrayList<SendableGet>();
			for(int i=0;i<reqs.length;i++) list.add(reqs[i]);
		}
		}
		if(list == null) return null;
		else return list.toArray(new SendableGet[list.size()]);
	}
	
	protected abstract Set makeSetForAllRequestsByClientRequest(ObjectContainer container);

	public void onStarted() {
		keyListeners = new HashSet<KeyListener>();
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append(super.toString());
		sb.append(':');
		if(isInsertScheduler)
			sb.append("insert:");
		if(isSSKScheduler)
			sb.append("SSK");
		else
			sb.append("CHK");
		return sb.toString();
	}

}
