/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import static java.lang.String.format;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.node.SendableGet;
import freenet.support.ByteArrayWrapper;
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
	protected final Map<ByteArrayWrapper,Object> singleKeyListeners;

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
		singleKeyListeners = this.isSSKScheduler ? new TreeMap<ByteArrayWrapper,Object>(ByteArrayWrapper.FAST_COMPARATOR) : new HashMap<ByteArrayWrapper,Object>();
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

	private boolean contains(KeyListener[] listeners, KeyListener listener) {
		for(KeyListener l : listeners) {
			if(l == listener) return true;
		}
		return false;
	}

	public void addPendingKeys(KeyListener listener) {
		if(listener == null) throw new NullPointerException();
		byte[] wantedKey = listener.getWantedKey();
		ByteArrayWrapper wrapper = wantedKey != null ? new ByteArrayWrapper(saltKey(wantedKey)) : null;
		assert(Arrays.equals(wantedKey, listener.getHasKeyListener().getWantedKey()));
		synchronized (this) {
			// We have to register before checking the disk, so it may well get registered twice.
			if(wantedKey != null) {
				Object o = singleKeyListeners.get(wrapper);
				if(o == null) {
					singleKeyListeners.put(wrapper, listener);
				} else if(o instanceof KeyListener) {
					if(listener == (KeyListener)o) return;
					singleKeyListeners.put(wrapper,
							new KeyListener[] { (KeyListener)o, listener });
				} else {
					@SuppressWarnings("unchecked")
					KeyListener[] listeners = (KeyListener[])o;
					if(contains(listeners, listener)) return;
					KeyListener[] newListeners = Arrays.copyOf(listeners, listeners.length+1);
					newListeners[listeners.length] = listener;
					singleKeyListeners.put(wrapper, newListeners);
				}
			} else {
				if(keyListeners.contains(listener))
					return;
				keyListeners.add(listener);
			}
		}
		if (logMINOR)
			Logger.minor(this, "Added pending keys to "+this+" : size now "+this.keyListeners.size()+"/"+singleKeyListeners.size()+" : "+listener);
	}
	
	public boolean removePendingKeys(KeyListener listener) {
		boolean ret = false;
		byte[] wantedKey = listener.getWantedKey();
		ByteArrayWrapper wrapper = wantedKey != null ? new ByteArrayWrapper(saltKey(wantedKey)) : null;
		synchronized (this) {
			if(wantedKey != null) {
				Object o = singleKeyListeners.get(wrapper);
				if(o == null) {
					// do nothing
				} else if(o instanceof KeyListener) {
					if((ret = (listener == (KeyListener)o)))
						singleKeyListeners.remove(wrapper);
				} else {
					@SuppressWarnings("unchecked")
					KeyListener[] listeners = (KeyListener[])o;
					KeyListener[] newListeners = new KeyListener[listeners.length-1];
					int x = 0;
					for (KeyListener l : listeners) {
						if(listener == l) {
							assert(!ret);
							ret = true;
							continue;
						}
						if(x == newListeners.length) {
							assert(!ret);
							break;
						}
						newListeners[x++] = l;
					}
					if(!ret) {
						// do nothing
					} else {
						assert(x == newListeners.length);
						if(newListeners.length == 0) {
							singleKeyListeners.remove(wrapper);
						} else if(newListeners.length == 1) {
							singleKeyListeners.put(wrapper,newListeners[0]);
						} else {
							singleKeyListeners.put(wrapper,newListeners);
						}
					}
				}
			} else {
				ret = keyListeners.remove(listener);
			}
			listener.onRemove();
		}
		listener.onRemove();
		if (logMINOR)
			Logger.minor(this, "Removed pending keys from "+this+" : size now "+this.keyListeners.size()+"/"+singleKeyListeners.size()+" : "+listener, new Exception("debug"));
		return ret;
	}
	
	public boolean removePendingKeys(HasKeyListener hasListener) {
		boolean ret = false;
		byte[] wantedKey = hasListener.getWantedKey();
		ByteArrayWrapper wrapper = wantedKey != null ? new ByteArrayWrapper(saltKey(wantedKey)) : null;
		synchronized(this) {
			if(wantedKey != null) {
				Object o = singleKeyListeners.get(wrapper);
				if(o == null) {
					// do nothing
				} else if(o instanceof KeyListener) {
					if((ret = ((KeyListener)o).getHasKeyListener() == hasListener)) {
						singleKeyListeners.remove(wrapper);
						((KeyListener)o).onRemove();
					}
				} else {
					@SuppressWarnings("unchecked")
					KeyListener[] listeners = (KeyListener[])o;
					KeyListener[] newListeners = new KeyListener[listeners.length-1];
					int x = 0;
					String msg = logMINOR ? "" : null;
					for (KeyListener l : listeners) {
						if(l.getHasKeyListener() == hasListener) {
							ret = true;
							l.onRemove();
							if (logMINOR)
								msg = msg + " : " + l;
							continue;
						}
						if(x == newListeners.length) {
							assert(!ret);
							break;
						}
						newListeners[x++] = l;
					}
					if(!ret) {
						// do nothing
					} else {
						if(x < newListeners.length)
							newListeners = Arrays.copyOf(newListeners, x);
						if(newListeners.length == 0) {
							singleKeyListeners.remove(wrapper);
						} else if(newListeners.length == 1) {
							singleKeyListeners.put(wrapper,newListeners[0]);
						} else {
							singleKeyListeners.put(wrapper,newListeners);
						}
						if (logMINOR)
							Logger.minor(this, "Removed pending keys from " + this + " : size now "+this.keyListeners.size()+"/"+singleKeyListeners.size() + msg);
					}
				}
				return ret;
			}
			for(Iterator<KeyListener> i = keyListeners.iterator();i.hasNext();) {
				KeyListener listener = i.next();
				if(listener.getHasKeyListener() == hasListener) {
					ret = true;
					i.remove();
					listener.onRemove();
					if (logMINOR)
						Logger.minor(this, "Removed pending keys from "+this+" : size now "+this.keyListeners.size()+"/"+singleKeyListeners.size()+" : "+listener);
				}
			}
		}
		return ret;
	}
	
	private synchronized ArrayList<KeyListener> probablyMatches(Key key, byte[] saltedKey) {
		ArrayList<KeyListener> matches = null;
		final ByteArrayWrapper wrapper = new ByteArrayWrapper(saltedKey);
		Object o = singleKeyListeners.get(wrapper);
		if(o == null) {
			// do nothing
		} else if(o instanceof KeyListener) {
			KeyListener listener = (KeyListener)o;
			do {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				matches.add(listener);
			} while(false);
		} else {
			@SuppressWarnings("unchecked")
			KeyListener[] listeners = (KeyListener[])o;
			for(KeyListener listener : listeners) {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				matches.add(listener);
			}
		}
		for(KeyListener listener : keyListeners) {
			if(!listener.probablyWantKey(key, saltedKey)) continue;
			if(matches == null) matches = new ArrayList<KeyListener> ();
			matches.add(listener);
		}
		return matches;
	}
	public short getKeyPrio(Key key, short priority, ClientContext context) {
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
		ArrayList<KeyListener> matches = probablyMatches(key, saltedKey);
		if(matches == null) return priority;
		for(KeyListener listener : matches) {
			short prio;
			try {
				prio = listener.definitelyWantKey(key, saltedKey, sched.clientContext);
			} catch (Throwable t) {
				Logger.error(this, format("Error in definitelyWantKey callback for %s", listener), t);
				continue;
			}
			if(prio == -1) continue;
			if(prio < priority) priority = prio;
		}
		return priority;
	}
	
	public synchronized long countWaitingKeys() {
		long count = 0;
		for(Object o: singleKeyListeners.values()) {
			if(o == null) {
				continue;
			} else if(o instanceof KeyListener) {
				count += ((KeyListener)o).countKeys();
			} else {
				@SuppressWarnings("unchecked")
				KeyListener[] listeners = (KeyListener[])o;
				for(KeyListener listener : listeners)
					count += listener.countKeys();
			}
		}
		for (KeyListener listener : keyListeners) {
			try {
				count += listener.countKeys();
			} catch (Throwable t) {
				Logger.error(this, format("Error in countKeys callback for %s", listener), t);
			}
		}
		return count;
	}
	
	public boolean anyWantKey(Key key, ClientContext context) {
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
		List<KeyListener> matches = probablyWantKey(key, saltedKey);
		if (!matches.isEmpty()) {
			for (KeyListener listener : matches) {
				try {
					if (listener.definitelyWantKey(key, saltedKey, sched.clientContext) >= 0) {
						return true;
					}
				} catch (Throwable t) {
					Logger.error(this, format("Error in definitelyWantKey callback for %s", listener), t);
				}
			}
		}
		return false;
	}
	
	public synchronized boolean anyProbablyWantKey(Key key, ClientContext context) {
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
		final ByteArrayWrapper wrapper = new ByteArrayWrapper(saltedKey);
		Object o = singleKeyListeners.get(wrapper);
		if(o == null) {
			// do nothing
		} else if(o instanceof KeyListener) {
			KeyListener listener = (KeyListener)o;
			if(listener.probablyWantKey(key, saltedKey)) return true;
		} else {
			@SuppressWarnings("unchecked")
			KeyListener[] listeners = (KeyListener[])o;
			for(KeyListener listener : listeners) {
				if(listener.probablyWantKey(key, saltedKey))
					return true;
			}
		}
		for (KeyListener listener : keyListeners) {
			try {
				if (listener.probablyWantKey(key, saltedKey)) {
					return true;
				}
			} catch (Throwable t) {
				Logger.error(this, format("Error in probablyWantKey callback for %s", listener), t);
			}
		}
		return false;
	}
	
	public boolean tripPendingKey(Key key, KeyBlock block, ClientContext context) {
		if ((key instanceof NodeSSK) != isSSKScheduler) {
			Logger.error(
					this,
					"Key " + key + " on scheduler ssk=" + isSSKScheduler,
					new Exception("debug"));
			return false;
		}
		assert (key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
		ArrayList<KeyListener> matches = probablyMatches(key, saltedKey);
		boolean ret = false;
		if (matches != null) {
			for (KeyListener listener : matches) {
				try {
					if (listener.handleBlock(key, saltedKey, block, context)) {
						ret = true;
					}
				} catch (Throwable t) {
					Logger.error(this, format("Error in handleBlock callback for %s", listener), t);
				}
				if (listener.isEmpty()) {
					try {
						removePendingKeys(listener);
					} catch (Throwable t) {
						Logger.error(this, format("Error while removing %s", listener), t);
					}
				}
			}
		}
		return ret;
	}
	
	public SendableGet[] requestsForKey(Key key, ClientContext context) {
		ArrayList<SendableGet> list = new ArrayList<SendableGet>();
		assert(key instanceof NodeSSK == isSSKScheduler);
		byte[] saltedKey = saltKey(key);
		List<KeyListener> matches = probablyWantKey(key, saltedKey);
    if(matches == null)
      return null;
		for (KeyListener listener : matches) {
			SendableGet[] reqs;
			try {
				reqs = listener.getRequestsForKey(key, saltedKey, context);
			} catch (Throwable t) {
				Logger.error(this, format("Error in getRequestsForKey callback for %s", listener), t);
				continue;
			}
			if (reqs == null) {
				continue;
			}
			for (SendableGet req : reqs) {
				list.add(req);
			}
		}
		if (list.isEmpty()) {
			return null;
		}
		return list.toArray(new SendableGet[list.size()]);
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
		return  saltKey(key instanceof NodeSSK ? ((NodeSSK)key).getPubKeyHash() : key.getRoutingKey());
	}

	private byte[] saltKey(byte[] key) {
		if (isSSKScheduler)
			return key;
		MessageDigest md = SHA256.getMessageDigest();
		md.update(key);
		md.update(globalSalt);
		return md.digest();
	}
	
	protected void hintGlobalSalt(byte[] globalSalt2) {
		if(globalSalt == null)
			globalSalt = globalSalt2;
	}

	/**
	 * Returns all KeyListeners that return true on probablyWantKey(key, saltedKey)
	 */
	private List<KeyListener> probablyWantKey(Key key, byte[] saltedKey) {
		ArrayList<KeyListener> matches = new ArrayList<KeyListener>();
		synchronized (this) {
			for (KeyListener listener : keyListeners) {
				try {
					if (!listener.probablyWantKey(key, saltedKey)) {
						continue;
					}
				} catch (Throwable t) {
					Logger.error(this, format("Error in probablyWantKey callback for %s", listener), t);
					continue;
				}
				matches.add(listener);
			}
		}
		return matches;
	}
}
