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
import freenet.node.SendableGet;
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
	
	/**
	 * Register a pending key to an already-registered request. This is necessary if we've
	 * already registered a SendableGet, but we later add some more keys to it.
	 */
	void addPendingKey(Key nodeKey, GotKeyListener getter, ObjectContainer container) {
		logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequestSchedulerBase.class);
		if(logMINOR)
			Logger.minor(this, "Adding pending key "+nodeKey+" for "+getter);
		synchronized(pendingKeys) {
			Object o = pendingKeys.get(nodeKey);
			if(o == null) {
				pendingKeys.put(nodeKey, getter);
			} else if(o instanceof GotKeyListener) {
				GotKeyListener oldGet = (GotKeyListener) o;
				if(oldGet != getter) {
					pendingKeys.put(nodeKey, new GotKeyListener[] { oldGet, getter });
				}
			} else {
				GotKeyListener[] gets = (GotKeyListener[]) o;
				boolean found = false;
				for(int j=0;j<gets.length;j++) {
					if(gets[j] == getter) {
						found = true;
						break;
					}
				}
				if(!found) {
					GotKeyListener[] newGets = new GotKeyListener[gets.length+1];
					System.arraycopy(gets, 0, newGets, 0, gets.length);
					newGets[gets.length] = getter;
					pendingKeys.put(nodeKey, newGets);
				}
			}
		}
	}

	public boolean removePendingKey(GotKeyListener getter, boolean complain, Key key, ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Removing pending key: "+getter+" for "+key);
		boolean dropped = false;
		Object o;
		/*
		 * Because arrays are not basic types, pendingKeys.activationDepth(1) means that
		 * the SendableGet's returned here will be activated to depth 1, even if they were
		 * within a SendableGet[]. Tested as of 21/05/08.
		 */
		synchronized(pendingKeys) {
			o = pendingKeys.get(key);
			if(o == null) {
				if(complain)
					Logger.normal(this, "Not found: "+getter+" for "+key+" removing (no such key)", new Exception("debug"));
			} else if(o instanceof GotKeyListener) {
				GotKeyListener oldGet = (GotKeyListener) o;
				if(oldGet != getter) {
					if(complain)
						Logger.normal(this, "Not found: "+getter+" for "+key+" removing (1 getter)", new Exception("debug"));
				} else {
					dropped = true;
					pendingKeys.remove(key);
					if(logMINOR)
						Logger.minor(this, "Removed only getter (1) for "+key, new Exception("debug"));
				}
			} else {
				GotKeyListener[] gets = (GotKeyListener[]) o;
				final int getsLength = gets.length;
				GotKeyListener[] newGets = new GotKeyListener[getsLength > 1 ? getsLength-1 : 0];
				boolean found = false;
				int x = 0;
				for(int j=0;j<getsLength;j++) {
					if(gets[j] == getter) {
						found = true;
						dropped = true;
						continue;
					}
					if(x == newGets.length) {
						if(!found) {
							if(complain)
								Logger.normal(this, "Not found: "+getter+" for "+key+" removing ("+getsLength+" getters)");
							return false; // not here
						}
					}
					if(gets[j] == null) continue;
					if(gets[j].isCancelled(container)) continue;
					newGets[x++] = gets[j];
				}
				if(x == 0) {
					pendingKeys.remove(key);
					if(logMINOR)
						Logger.minor(this, "Removed only getter (2) for "+key, new Exception("debug"));
				} else if(x == 1) {
					pendingKeys.put(key, newGets[0]);
				} else {
					if(x != getsLength-1) {
						GotKeyListener[] newNewGets = new GotKeyListener[x];
						System.arraycopy(newGets, 0, newNewGets, 0, x);
						newGets = newNewGets;
					}
					pendingKeys.put(key, newGets);
				}
			}
		}
		return dropped;
	}

	public GotKeyListener[] removePendingKey(Key key, ObjectContainer container) {
		Object o;
		final GotKeyListener[] gets;
		synchronized(pendingKeys) {
			o = pendingKeys.remove(key);
		}
		if(o == null) return null;
		if(o instanceof GotKeyListener) {
			gets = new GotKeyListener[] { (GotKeyListener) o };
			if(logMINOR)
				Logger.minor(this, "Removing all pending keys for "+key+" (1)", new Exception("debug"));
		} else {
			gets = (GotKeyListener[]) o;
			if(logMINOR)
				Logger.minor(this, "Removing all pending keys for "+key+" ("+gets.length+")", new Exception("debug"));
		}
		return gets;
	}

	public boolean anyWantKey(Key key, ObjectContainer container) {
		synchronized(pendingKeys) {
			return pendingKeys.get(key) != null;
		}
	}

	public short getKeyPrio(Key key, short priority, ObjectContainer container) {
		synchronized(pendingKeys) {
			Object o = pendingKeys.get(key);
			if(o == null) {
				// Blah
			} else if(o instanceof GotKeyListener) {
				short p = ((GotKeyListener)o).getPriorityClass(container);
				if(p < priority) priority = p;
			} else { // if(o instanceof SendableGet[]) {
				GotKeyListener[] gets = (GotKeyListener[]) o;
				for(int i=0;i<gets.length;i++) {
					short p = gets[i].getPriorityClass(container);
					if(p < priority) priority = p;
				}
			}
		}
		return priority;
	}

	public GotKeyListener[] getClientsForPendingKey(Key key, ObjectContainer container) {
		Object o;
		synchronized(pendingKeys) {
			o = pendingKeys.get(key);
		}
		if(o == null) {
			return null;
		} else if(o instanceof GotKeyListener) {
			GotKeyListener get = (GotKeyListener) o;
			return new GotKeyListener[] { get };
		} else {
			return (GotKeyListener[]) o;
		}
	}

	protected boolean inPendingKeys(GotKeyListener req, Key key, ObjectContainer container) {
		Object o;
		synchronized(pendingKeys) {
			o = pendingKeys.get(key);
		}
		if(o == null) {
			return false;
		} else if(o instanceof GotKeyListener) {
			return o == req;
		} else {
			GotKeyListener[] gets = (GotKeyListener[]) o;
			for(int i=0;i<gets.length;i++)
				if(gets[i] == req) return true;
		}
		return false;
	}

	public long countQueuedRequests(ObjectContainer container) {
		if(pendingKeys != null)
			return pendingKeys.size();
		else return 0;
	}
	

}
