/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.Map;

import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.SendableGet;
import freenet.support.Logger;

/**
 * Base class for ClientRequestSchedulerCore and ClientRequestSchedulerNonPersistent, 
 * contains some of the methods and most of the variables. In particular, it contains all 
 * the methods that deal primarily with pendingKeys.
 * @author toad
 */
public abstract class ClientRequestSchedulerBase {
	
	private static boolean logMINOR;
	/** All pending gets by key. Used to automatically satisfy pending requests when either the key is fetched by
	 * an overlapping request, or it is fetched by a request from another node. Operations on this are synchronized on
	 * itself. */
	protected final Map /* <Key, SendableGet[]> */ pendingKeys;

	protected ClientRequestSchedulerBase(Map pendingKeys) {
		this.pendingKeys = pendingKeys;
		logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequestSchedulerBase.class);
	}
	
	/**
	 * Register a pending key to an already-registered request. This is necessary if we've
	 * already registered a SendableGet, but we later add some more keys to it.
	 */
	void addPendingKey(ClientKey key, SendableGet getter) {
		logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequestSchedulerBase.class);
		if(logMINOR)
			Logger.minor(this, "Adding pending key "+key+" for "+getter);
		Key nodeKey = key.getNodeKey();
		synchronized(pendingKeys) {
			Object o = pendingKeys.get(nodeKey);
			if(o == null) {
				pendingKeys.put(nodeKey, getter);
			} else if(o instanceof SendableGet) {
				SendableGet oldGet = (SendableGet) o;
				if(oldGet != getter) {
					pendingKeys.put(nodeKey, new SendableGet[] { oldGet, getter });
				}
			} else {
				SendableGet[] gets = (SendableGet[]) o;
				boolean found = false;
				for(int j=0;j<gets.length;j++) {
					if(gets[j] == getter) {
						found = true;
						break;
					}
				}
				if(!found) {
					SendableGet[] newGets = new SendableGet[gets.length+1];
					System.arraycopy(gets, 0, newGets, 0, gets.length);
					newGets[gets.length] = getter;
					pendingKeys.put(nodeKey, newGets);
				}
			}
		}
	}

	public boolean removePendingKey(SendableGet getter, boolean complain, Key key) {
		boolean dropped = false;
		Object o;
		synchronized(pendingKeys) {
			o = pendingKeys.get(key);
			if(o == null) {
				if(complain)
					Logger.normal(this, "Not found: "+getter+" for "+key+" removing (no such key)");
			} else if(o instanceof SendableGet) {
				SendableGet oldGet = (SendableGet) o;
				if(oldGet != getter) {
					if(complain)
						Logger.normal(this, "Not found: "+getter+" for "+key+" removing (1 getter)");
				} else {
					dropped = true;
					pendingKeys.remove(key);
				}
			} else {
				SendableGet[] gets = (SendableGet[]) o;
				final int getsLength = gets.length;
				SendableGet[] newGets = new SendableGet[getsLength > 1 ? getsLength-1 : 0];
				boolean found = false;
				int x = 0;
				for(int j=0;j<getsLength;j++) {
					if(gets[j] == getter) {
						found = true;
						continue;
					}
					if(j == newGets.length) {
						if(!found) {
							if(complain)
								Logger.normal(this, "Not found: "+getter+" for "+key+" removing ("+getsLength+" getters)");
							return false; // not here
						}
					}
					if(gets[j] == null) continue;
					if(gets[j].isCancelled()) continue;
					newGets[x++] = gets[j];
				}
				if(x == 0) {
					pendingKeys.remove(key);
					dropped = true;
				} else if(x == 1) {
					pendingKeys.put(key, newGets[0]);
				} else {
					if(x != getsLength-1) {
						SendableGet[] newNewGets = new SendableGet[x];
						System.arraycopy(newGets, 0, newNewGets, 0, x);
						newGets = newNewGets;
					}
					pendingKeys.put(key, newGets);
				}
			}
		}
		return dropped;
	}

	public SendableGet[] removePendingKey(Key key) {
		Object o;
		final SendableGet[] gets;
		synchronized(pendingKeys) {
			o = pendingKeys.remove(key);
		}
		if(o == null) return null;
		if(o instanceof SendableGet) {
			gets = new SendableGet[] { (SendableGet) o };
		} else {
			gets = (SendableGet[]) o;
		}
		return gets;
	}

	public boolean anyWantKey(Key key) {
		synchronized(pendingKeys) {
			return pendingKeys.get(key) != null;
		}
	}

	public short getKeyPrio(Key key, short priority) {
		synchronized(pendingKeys) {
			Object o = pendingKeys.get(key);
			if(o == null) {
				// Blah
			} else if(o instanceof SendableGet) {
				short p = ((SendableGet)o).getPriorityClass();
				if(p < priority) priority = p;
			} else { // if(o instanceof SendableGet[]) {
				SendableGet[] gets = (SendableGet[]) o;
				for(int i=0;i<gets.length;i++) {
					short p = gets[i].getPriorityClass();
					if(p < priority) priority = p;
				}
			}
		}
		return priority;
	}

	public SendableGet[] getClientsForPendingKey(Key key) {
		Object o;
		synchronized(pendingKeys) {
			o = pendingKeys.get(key);
		}
		if(o == null) {
			return null;
		} else if(o instanceof SendableGet) {
			SendableGet get = (SendableGet) o;
			return new SendableGet[] { get };
		} else {
			return (SendableGet[]) o;
		}
	}

	public long countQueuedRequests() {
		if(pendingKeys != null)
			return pendingKeys.size();
		else return 0;
	}

}
