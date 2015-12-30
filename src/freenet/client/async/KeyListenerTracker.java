/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import static java.lang.String.format;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;

import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.node.SendableGet;
import freenet.node.SendableRequest;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * <p>Tracks exactly which keys we are listening for. This is 
 * decoupled from actually requesting them because we want to pick up the data even if we didn't 
 * request it - some nearby node requested it, it got inserted through this node, it was offered 
 * via ULPRs some time after we requested it etc.</p>
 * 
 * <p>The queue of requests to run, and the algorithm to choose which to start, is in
 * @see ClientRequestSchedulerSelector .</p>
 * 
 * PERSISTENCE: This class is NOT serialized, it is recreated on every startup, and downloads are
 * re-registered with this class (for KeyListeners) and downloads and uploads are re-registered 
 * with the ClientRequestSelector.
 * @author toad
 */
class KeyListenerTracker implements KeySalter {
	
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
	
	protected final ClientRequestScheduler sched;
	/** Transient even for persistent scheduler. There is one for each of transient, persistent. */
	protected final ArrayList<KeyListener> keyListeners;

	final boolean persistent;
	
	public boolean persistent() {
	    return persistent;
	}
	
	protected KeyListenerTracker(boolean forInserts, boolean forSSKs, boolean forRT, RandomSource random, ClientRequestScheduler sched, byte[] globalSalt, boolean persistent) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.isRTScheduler = forRT;
		this.sched = sched;
		keyListeners = new ArrayList<KeyListener>();
		if(globalSalt == null) {
		    globalSalt = new byte[32];
		    random.nextBytes(globalSalt);
		}
		this.globalSalt = globalSalt;
		this.persistent = persistent;
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
	
	public short getKeyPrio(Key key, short priority, ClientContext context) {
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
	
	public synchronized long countWaitingKeys() {
		long count = 0;
		for(KeyListener listener : keyListeners)
			count += listener.countKeys();
		return count;
	}
	
	public boolean anyWantKey(Key key, ClientContext context) {
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
	
	public boolean tripPendingKey(Key key, KeyBlock block, ClientContext context) {
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
	
	public SendableGet[] requestsForKey(Key key, ClientContext context) {
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
