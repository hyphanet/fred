/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
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
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;
import freenet.support.SectoredRandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.SortedVectorByNumber;
import freenet.support.Logger.LogLevel;

/**
 * Base class for ClientRequestSchedulerCore and ClientRequestSchedulerNonPersistent, 
 * contains some of the methods and most of the variables. In particular, it contains all 
 * the methods that deal primarily with pendingKeys.
 * @author toad
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
abstract class ClientRequestSchedulerBase {
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/** Minimum number of retries at which we start to hold it against a request.
	 * See the comments on fixRetryCount; we don't want many untried requests to prevent
	 * us from trying requests which have only been tried once (e.g. USK checkers), from 
	 * other clients (and we DO want retries to take precedence over client round robin IF 
	 * the request has been tried many times already). */
	private static final int MIN_RETRY_COUNT = 3;

	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final boolean isRTScheduler;
	
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * SectoredRandomGrabArray's // round-robin by RequestClient, then by SendableRequest
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	protected SortedVectorByNumber[] priorities;
	/**
	 * New structure:
	 * array (by priority) -> // one element per possible priority
	 * SectoredRandomGrabArray's // round-robin by RequestClient, then by SendableRequest
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 */
	protected SectoredRandomGrabArray[] newPriorities;
	protected transient ClientRequestScheduler sched;
	/** Transient even for persistent scheduler. */
	protected transient ArrayList<KeyListener> keyListeners;

	abstract boolean persistent();
	
	protected ClientRequestSchedulerBase(boolean forInserts, boolean forSSKs, boolean forRT, RandomSource random) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.isRTScheduler = forRT;
		keyListeners = new ArrayList<KeyListener>();
		priorities = null;
		newPriorities = new SectoredRandomGrabArray[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		globalSalt = new byte[32];
		random.nextBytes(globalSalt);
	}
	
	/**
	 * @param req
	 * @param random
	 * @param container
	 * @param maybeActive Array of requests, can be null, which are being registered
	 * in this group. These will be ignored for purposes of checking whether stuff
	 * is activated when it shouldn't be. It is perfectly okay to have req be a
	 * member of maybeActive.
	 * 
	 * FIXME: Either get rid of the debugging code and therefore get rid of maybeActive,
	 * or make req a SendableRequest[] and register them all at once.
	 */
	void innerRegister(SendableRequest req, RandomSource random, ObjectContainer container, ClientContext context, SendableRequest[] maybeActive) {
		if(isInsertScheduler && req instanceof BaseSendableGet)
			throw new IllegalArgumentException("Adding a SendableGet to an insert scheduler!!");
		if((!isInsertScheduler) && req instanceof SendableInsert)
			throw new IllegalArgumentException("Adding a SendableInsert to a request scheduler!!");
		if(isInsertScheduler != req.isInsert())
			throw new IllegalArgumentException("Request isInsert="+req.isInsert()+" but my isInsertScheduler="+isInsertScheduler+"!!");
		if(req.persistent() != persistent())
			throw new IllegalArgumentException("innerRegister for persistence="+req.persistent()+" but our persistence is "+persistent());
		short prio = req.getPriorityClass(container);
		if(logMINOR) Logger.minor(this, "Still registering "+req+" at prio "+prio+" for "+req.getClientRequest()+" ssk="+this.isSSKScheduler+" insert="+this.isInsertScheduler);
		addToRequestsByClientRequest(req.getClientRequest(), req, container);
		addToGrabArray(prio, req.getClient(container), req.getClientRequest(), req, random, container, context);
		if(logMINOR) Logger.minor(this, "Registered "+req+" on prioclass="+prio);
		if(persistent())
			sched.maybeAddToStarterQueue(req, container, maybeActive);
	}
	
	protected void addToRequestsByClientRequest(ClientRequester clientRequest, SendableRequest req, ObjectContainer container) {
		if(clientRequest != null || persistent()) { // Client request null is only legal for transient requests
			boolean deactivate = false;
			if(persistent()) {
				deactivate = !container.ext().isActive(clientRequest);
				if(deactivate) container.activate(clientRequest, 1);
			}
			// If the request goes through the datastore checker (SendableGet's unless they have the don't check store flag) it will have already been registered.
			// That does not matter.
			clientRequest.addToRequests(req, container);
			if(deactivate) container.deactivate(clientRequest, 1);
		}
	}
	
	void addToGrabArray(short priorityClass, RequestClient client, ClientRequester cr, SendableRequest req, RandomSource random, ObjectContainer container, ClientContext context) {
		if((priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS) || (priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS))
			throw new IllegalStateException("Invalid priority: "+priorityClass+" - range is "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" (most important) to "+RequestStarter.MINIMUM_PRIORITY_CLASS+" (least important)");
		// Client
		synchronized(this) {
			SectoredRandomGrabArray clientGrabber = newPriorities[priorityClass];
			if(persistent()) container.activate(clientGrabber, 1);
			if(clientGrabber == null) {
				clientGrabber = new SectoredRandomGrabArray(persistent(), container, null);
				newPriorities[priorityClass] = clientGrabber;
				if(persistent()) container.store(this);
				if(logMINOR) Logger.minor(this, "Registering client tracker for priority "+priorityClass+" : "+clientGrabber);
			}
			// SectoredRandomGrabArrayWithInt and lower down have hierarchical locking and auto-remove.
			// To avoid a race condition it is essential to mirror that here.
			synchronized(clientGrabber) {
				// Request
				SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) clientGrabber.getGrabber(client);
				if(persistent()) container.activate(requestGrabber, 1);
				if(requestGrabber == null) {
					requestGrabber = new SectoredRandomGrabArrayWithObject(client, persistent(), container, clientGrabber);
					if(logMINOR)
						Logger.minor(this, "Creating new grabber: "+requestGrabber+" for "+client+" from "+clientGrabber+" : prio="+priorityClass);
					clientGrabber.addGrabber(client, requestGrabber, container, context);
					// FIXME unnecessary as it knows its parent and addGrabber() will call it???
					context.cooldownTracker.clearCachedWakeup(clientGrabber, persistent(), container);
				}
				requestGrabber.add(cr, req, container, context);
			}
		}
		sched.wakeStarter();
	}

	/**
	 * Mangle the retry count.
	 * Below a certain number of attempts, we don't prefer one request to another just because
	 * it's been tried more times. The reason for this is to prevent floods of low-retry-count
	 * requests from starving other clients' requests which need to be retried. The other
	 * solution would be to sort by client before retry count, but that would be excessive 
	 * IMHO; we DO want to avoid rerequesting keys we've tried many times before.
	 */
	protected static int fixRetryCount(int retryCount) {
		return Math.max(0, retryCount-MIN_RETRY_COUNT);
	}

	/**
	 * Get SendableRequest's for a given ClientRequester.
	 * Note that this will return all kinds of requests, so the caller will have
	 * to filter them according to isInsert and isSSKScheduler.
	 */
	protected SendableRequest[] getSendableRequests(ClientRequester request, ObjectContainer container) {
		if(request != null || persistent()) // Client request null is only legal for transient requests
			return request.getSendableRequests(container);
		else return null;
	}

	void removeFromAllRequestsByClientRequest(SendableRequest req, ClientRequester cr, boolean dontComplain, ObjectContainer container) {
		if(cr != null || persistent()) // Client request null is only legal for transient requests
			cr.removeFromRequests(req, container, dontComplain);
	}
	
	public void reregisterAll(ClientRequester request, RandomSource random, RequestScheduler lock, ObjectContainer container, ClientContext context, short oldPrio) {
		if(request.persistent() != persistent()) return;
		SendableRequest[] reqs = getSendableRequests(request, container);
		
		if(reqs == null) return;
		for(int i=0;i<reqs.length;i++) {
			SendableRequest req = reqs[i];
			if(req == null) {
				// We will get rid of SendableRequestSet soon, so this is low priority.
				Logger.error(this, "Request "+i+" is null reregistering for "+request);
				continue;
			}
			if(persistent())
				container.activate(req, 1);
			boolean isInsert = req.isInsert();
			// FIXME call getSendableRequests() and do the sorting in ClientRequestScheduler.reregisterAll().
			if(isInsert != isInsertScheduler || req.isSSK() != isSSKScheduler) {
				if(persistent()) container.deactivate(req, 1);
				continue;
			}
			if(persistent() && req.isStorageBroken(container)) {
				Logger.error(this, "Broken request while changing priority: "+req);
				continue;
			}
			if(req.persistent() != persistent()) {
				Logger.error(this, "Request persistence is "+req.persistent()+" but scheduler's is "+persistent()+" on "+this+" for "+req);
				continue;
			}
			// Unregister from the RGA's, but keep the pendingKeys and cooldown queue data.
			req.unregister(container, context, oldPrio);
			//Remove from the starterQueue
			if(persistent()) sched.removeFromStarterQueue(req, container, true);
			// Then can do innerRegister() (not register()).
			if(persistent())
				container.activate(req, 1);
			innerRegister(req, random, container, context, null);
			if(persistent())
				container.deactivate(req, 1);
		}
	}

	public void succeeded(BaseSendableGet succeeded, ObjectContainer container) {
		// Do nothing.
	}

	public void addPendingKeys(KeyListener listener) {
		if(listener == null) throw new NullPointerException();
		synchronized (this) {
			// We have to register before checking the disk, so it may well get registered twice.
			if(keyListeners.contains(listener))
				return;
			keyListeners.add(listener);
		}
		if (logMINOR)
			Logger.minor(this, "Added pending keys to "+this+" : size now "+keyListeners.size()+" : "+listener);
	}
	
	public boolean removePendingKeys(KeyListener listener) {
		boolean ret;
		synchronized (this) {
			ret = keyListeners.remove(listener);
			while(logMINOR && keyListeners.remove(listener))
				Logger.error(this, "Still in pending keys after removal, must be in twice or more: "+listener, new Exception("error"));
			listener.onRemove();
		}
		if (logMINOR)
			Logger.minor(this, "Removed pending keys from "+this+" : size now "+keyListeners.size()+" : "+listener, new Exception("debug"));
		return ret;
	}
	
	public synchronized boolean removePendingKeys(HasKeyListener hasListener) {
		boolean found = false;
		for(Iterator<KeyListener> i = keyListeners.iterator();i.hasNext();) {
			KeyListener listener = i.next();
			if(listener == null) {
				i.remove();
				Logger.error(this, "Null KeyListener in removePendingKeys()");
				continue;
			}
			if(listener.getHasKeyListener() == hasListener) {
				found = true;
				i.remove();
				listener.onRemove();
				Logger.normal(this, "Removed pending keys from "+this+" : size now "+keyListeners.size()+" : "+listener);
			}
		}
		return found;
	}
	
	public short getKeyPrio(Key key, short priority, ObjectContainer container, ClientContext context) {
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
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
	
	public synchronized long countWaitingKeys(ObjectContainer container) {
		long count = 0;
		for(KeyListener listener : keyListeners)
			count += listener.countKeys();
		return count;
	}
	
	public boolean anyWantKey(Key key, ObjectContainer container, ClientContext context) {
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
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
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
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
		if((key instanceof NodeSSK) != isSSKScheduler) {
			Logger.error(this, "Key "+key+" on scheduler ssk="+isSSKScheduler, new Exception("debug"));
			return false;
		}
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
		ArrayList<KeyListener> matches = null;
		synchronized(this) {
			for(KeyListener listener : keyListeners) {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				if(matches.contains(listener)) {
					Logger.error(this, "In matches twice, presumably in keyListeners twice?: "+listener);
					continue;
				}
				matches.add(listener);
			}
		}
		boolean ret = false;
		if(matches != null) {
			for(KeyListener listener : matches) {
				try {
					if(listener.handleBlock(key, saltedKey, block, container, context))
						ret = true;
				} catch (Throwable t) {
					try {
						Logger.error(this, "Caught "+t+" in handleBlock callback for "+listener, new Exception("error"));
					} catch (Throwable t1) {
						Logger.error(this, "Caught "+t+" in handleBlock callback", new Exception("error"));
					}
				}
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
			percent = ((double) 100 * persistentFalsePositives) / totalPositives;
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
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
		synchronized(this) {
		for(KeyListener listener : keyListeners) {
			if(!listener.probablyWantKey(key, saltedKey)) continue;
			SendableGet[] reqs = listener.getRequestsForKey(key, saltedKey, container, context);
			if(reqs == null) continue;
			if(list == null) list = new ArrayList<SendableGet>();
			for(SendableGet req: reqs) list.add(req);
		}
		}
		if(list == null) return null;
		else return list.toArray(new SendableGet[list.size()]);
	}
	
	public void onStarted(ObjectContainer container, ClientContext context) {
		keyListeners = new ArrayList<KeyListener>();
		if(newPriorities == null) {
			newPriorities = new SectoredRandomGrabArray[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
			if(persistent()) container.store(this);
			if(priorities != null)
				migrateToNewPriorities(container, context);
		}
	}
	
	private void migrateToNewPriorities(ObjectContainer container,
			ClientContext context) {
		System.err.println("Migrating old priorities to new priorities ...");
		for(int prio=0;prio<priorities.length;prio++) {
			System.err.println("Priority "+prio);
			SortedVectorByNumber retryList = priorities[prio];
			if(retryList == null) continue;
			if(persistent()) container.activate(retryList, 1);
			if(!retryList.isEmpty()) {
				while(retryList.count() > 0) {
					int retryCount = retryList.getNumberByIndex(0);
					System.err.println("Retry count "+retryCount+" for priority "+prio);
					SectoredRandomGrabArrayWithInt retryTracker = (SectoredRandomGrabArrayWithInt) retryList.getByIndex(0);
					if(retryTracker == null) {
						System.out.println("Retry count is empty");
						retryList.remove(retryCount, container);
						continue; // Fault tolerance in migration is good!
					}
					if(persistent()) container.activate(retryTracker, 1);
					// Move everything from the retryTracker to the new priority
					if(newPriorities[prio] == null) {
						newPriorities[prio] = new SectoredRandomGrabArray(persistent(), container, null);
						if(persistent()) {
							container.store(newPriorities[prio]);
							container.store(this);
						}
					} else {
						if(persistent())
							container.activate(newPriorities[prio], 1);
					}
					SectoredRandomGrabArray newTopLevel = newPriorities[prio];
					retryTracker.moveElementsTo(newTopLevel, container, true);
					if(persistent()) {
						container.deactivate(newTopLevel, 1);
						retryTracker.removeFrom(container);
					}
					retryList.remove(retryCount, container);
					if(persistent()) container.commit();
					System.out.println("Migrated retry count "+retryCount+" on priority "+prio);
				}
			}
			retryList.removeFrom(container);
			priorities[prio] = null;
			if(persistent()) container.commit();
			System.out.println("Migrated priority "+prio);
		}
		if(persistent()) {
			priorities = null;
			container.store(this);
			container.commit();
			System.out.println("Migrated all priorities");
		}
	}

	@Override
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

	public synchronized long countQueuedRequests(ObjectContainer container, ClientContext context) {
		long total = 0;
		for(int i=0;i<newPriorities.length;i++) {
			SectoredRandomGrabArray prio = newPriorities[i];
			container.activate(prio, 1);
			if(prio == null || prio.isEmpty(container))
				System.out.println("Priority "+i+" : empty");
			else {
				System.out.println("Priority "+i+" : "+prio.size());
					System.out.println("Clients: "+prio.size()+" for "+prio);
					for(int k=0;k<prio.size();k++) {
						Object client = prio.getClient(k);
						container.activate(client, 1);
						System.out.println("Client "+k+" : "+client);
						container.deactivate(client, 1);
						SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) prio.getGrabber(client);
						container.activate(requestGrabber, 1);
						System.out.println("SRGA for client: "+requestGrabber);
						for(int l=0;l<requestGrabber.size();l++) {
							client = requestGrabber.getClient(l);
							container.activate(client, 1);
							System.out.println("Request "+l+" : "+client);
							container.deactivate(client, 1);
							RandomGrabArray rga = (RandomGrabArray) requestGrabber.getGrabber(client);
							container.activate(rga, 1);
							System.out.println("Queued SendableRequests: "+rga.size()+" on "+rga);
							long sendable = 0;
							long all = 0;
							for(int m=0;m<rga.size();m++) {
								SendableRequest req = (SendableRequest) rga.get(m, container);
								if(req == null) continue;
								container.activate(req, 1);
								sendable += req.countSendableKeys(container, context);
								all += req.countAllKeys(container, context);
								container.deactivate(req, 1);
							}
							System.out.println("Sendable keys: "+sendable+" all keys "+all+" diff "+(all-sendable));
							total += all;
							container.deactivate(rga, 1);
						}
						container.deactivate(requestGrabber, 1);
					}
					container.deactivate(prio, 1);
			}
		}
		return total;
	}	
	
	public byte[] globalSalt;
	
	public byte[] saltKey(Key key) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(key.getRoutingKey());
		md.update(globalSalt);
		byte[] ret = md.digest();
		SHA256.returnMessageDigest(md);
		return ret;
	}
	
	protected void hintGlobalSalt(byte[] globalSalt2) {
		if(globalSalt == null)
			globalSalt = globalSalt2;
	}

}
