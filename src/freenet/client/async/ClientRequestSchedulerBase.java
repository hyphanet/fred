/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import static java.lang.String.format;

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
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * <p>Base class for @see ClientRequestSchedulerCore and @see ClientRequestSchedulerNonPersistent, 
 * contains some of the methods and most of the variables, in particular it contains the base of
 * the request selection tree. The actual request selection algorithm is in 
 * @see ClientRequestSchedulerSelector .</p>
 * 
 * <p>It also contains separate structures which track exactly which keys we are listening
 * for. This is decoupled from actually requesting them because we want to pick up the data
 * even if we didn't request it - some nearby node requested it, it got inserted through
 * this node, it was offered via ULPRs some time after we requested it etc.</p>
 * @author toad
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
abstract class ClientRequestSchedulerBase implements KeySalter {
	
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
	
	protected transient ClientRequestScheduler sched;
	/** Transient even for persistent scheduler. There is one for each of transient, persistent. */
	protected transient ArrayList<KeyListener> keyListeners;

	abstract boolean persistent();
	
	protected ClientRequestSchedulerBase(boolean forInserts, boolean forSSKs, boolean forRT, RandomSource random) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.isRTScheduler = forRT;
		keyListeners = new ArrayList<KeyListener>();
		globalSalt = new byte[32];
		random.nextBytes(globalSalt);
	}
	
	/**
	 * @param req
	 * @param container
	 * @param maybeActive Array of requests, can be null, which are being registered
	 * in this group. These will be ignored for purposes of checking whether stuff
	 * is activated when it shouldn't be. It is perfectly okay to have req be a
	 * member of maybeActive.
	 * 
	 * FIXME: Either get rid of the debugging code and therefore get rid of maybeActive,
	 * or make req a SendableRequest[] and register them all at once.
	 */
	void innerRegister(SendableRequest req, ObjectContainer container, ClientContext context, SendableRequest[] maybeActive) {
		if(isInsertScheduler && req instanceof BaseSendableGet)
			throw new IllegalArgumentException("Adding a SendableGet to an insert scheduler!!");
		if((!isInsertScheduler) && req instanceof SendableInsert)
			throw new IllegalArgumentException("Adding a SendableInsert to a request scheduler!!");
		if(isInsertScheduler != req.isInsert())
			throw new IllegalArgumentException("Request isInsert="+req.isInsert()+" but my isInsertScheduler="+isInsertScheduler+"!!");
		if(req.persistent() != persistent())
			throw new IllegalArgumentException("innerRegister for persistence="+req.persistent()+" but our persistence is "+persistent());
		short prio = req.getPriorityClass();
		if(logMINOR) Logger.minor(this, "Still registering "+req+" at prio "+prio+" for "+req.getClientRequest()+" ssk="+this.isSSKScheduler+" insert="+this.isInsertScheduler);
		addToRequestsByClientRequest(req.getClientRequest(), req, container);
		sched.selector.addToGrabArray(prio, req.getClient(), req.getClientRequest(), req, container, context);
		if(logMINOR) Logger.minor(this, "Registered "+req+" on prioclass="+prio);
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
			clientRequest.addToRequests(req);
			if(deactivate) container.deactivate(clientRequest, 1);
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
			return request.getSendableRequests();
		else return null;
	}

	void removeFromAllRequestsByClientRequest(SendableRequest req, ClientRequester cr, boolean dontComplain, ObjectContainer container) {
		if(cr != null || persistent()) // Client request null is only legal for transient requests
			cr.removeFromRequests(req, dontComplain);
	}
	
	public void reregisterAll(ClientRequester request, RequestScheduler lock, ObjectContainer container, ClientContext context, short oldPrio) {
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
			if(req.persistent() != persistent()) {
				Logger.error(this, "Request persistence is "+req.persistent()+" but scheduler's is "+persistent()+" on "+this+" for "+req);
				continue;
			}
			// Unregister from the RGA's, but keep the pendingKeys and cooldown queue data.
			req.unregister(context, oldPrio);
			//Remove from the starterQueue
			// Then can do innerRegister() (not register()).
			if(persistent())
				container.activate(req, 1);
			innerRegister(req, container, context, null);
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
			short prio = listener.definitelyWantKey(key, saltedKey, sched.clientContext);
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
				if(listener.definitelyWantKey(key, saltedKey, sched.clientContext) >= 0)
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
					if(listener.handleBlock(key, saltedKey, block, context))
						ret = true;
				} catch (Throwable t) {
					Logger.error(this, format("Error in handleBlock callback for %s", listener), t);
				}
				if(listener.isEmpty()) {
					synchronized(this) {
						keyListeners.remove(listener);
					}
					listener.onRemove();
				}
			}
		} else return false;
		return ret;
	}
	
	public SendableGet[] requestsForKey(Key key, ObjectContainer container, ClientContext context) {
		ArrayList<SendableGet> list = null;
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
		synchronized(this) {
		for(KeyListener listener : keyListeners) {
			if(!listener.probablyWantKey(key, saltedKey)) continue;
			SendableGet[] reqs = listener.getRequestsForKey(key, saltedKey, context);
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
