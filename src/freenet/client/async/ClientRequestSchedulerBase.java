/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
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
	
	/** All pending gets by key. Used to automatically satisfy pending requests when either the key is fetched by
	 * an overlapping request, or it is fetched by a request from another node. Operations on this are synchronized on
	 * itself. */
	protected final Map /* <Key, SendableGet[]> */ pendingKeys;
	
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
	
	/** @return The container if this is persistent, otherwise null */
	abstract ObjectContainer container();
	
	protected ClientRequestSchedulerBase(boolean forInserts, boolean forSSKs, Map pendingKeys, Map allRequestsByClientRequest, List recentSuccesses) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.pendingKeys = pendingKeys;
		this.allRequestsByClientRequest = allRequestsByClientRequest;
		this.recentSuccesses = recentSuccesses;
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
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
		/*
		 * Because arrays are not basic types, pendingKeys.activationDepth(1) means that
		 * the SendableGet's returned here will be activated to depth 1, even if they were
		 * within a SendableGet[]. Tested as of 21/05/08.
		 */
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
	
	void innerRegister(SendableRequest req, RandomSource random) {
		if(logMINOR) Logger.minor(this, "Still registering "+req+" at prio "+req.getPriorityClass()+" retry "+req.getRetryCount()+" for "+req.getClientRequest());
		int retryCount = req.getRetryCount();
		addToGrabArray(req.getPriorityClass(), retryCount, fixRetryCount(retryCount), req.getClient(), req.getClientRequest(), req, random);
		HashSet v = (HashSet) allRequestsByClientRequest.get(req.getClientRequest());
		if(v == null) {
			v = new HashSet();
			allRequestsByClientRequest.put(req.getClientRequest(), v);
		}
		v.add(req);
		if(logMINOR) Logger.minor(this, "Registered "+req+" on prioclass="+req.getPriorityClass()+", retrycount="+req.getRetryCount()+" v.size()="+v.size());
	}
	
	void addToGrabArray(short priorityClass, int retryCount, int rc, Object client, ClientRequester cr, SendableRequest req, RandomSource random) {
		if((priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS) || (priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS))
			throw new IllegalStateException("Invalid priority: "+priorityClass+" - range is "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" (most important) to "+RequestStarter.MINIMUM_PRIORITY_CLASS+" (least important)");
		// Priority
		SortedVectorByNumber prio = priorities[priorityClass];
		if(prio == null) {
			prio = new SortedVectorByNumber();
			priorities[priorityClass] = prio;
		}
		// Client
		SectoredRandomGrabArrayWithInt clientGrabber = (SectoredRandomGrabArrayWithInt) prio.get(rc);
		if(clientGrabber == null) {
			clientGrabber = new SectoredRandomGrabArrayWithInt(random, rc, true);
			prio.add(clientGrabber);
			if(logMINOR) Logger.minor(this, "Registering retry count "+rc+" with prioclass "+priorityClass+" on "+clientGrabber+" for "+prio);
		}
		// SectoredRandomGrabArrayWithInt and lower down have hierarchical locking and auto-remove.
		// To avoid a race condition it is essential to mirror that here.
		synchronized(clientGrabber) {
			// Request
			SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) clientGrabber.getGrabber(client);
			if(requestGrabber == null) {
				requestGrabber = new SectoredRandomGrabArrayWithObject(client, random, true);
				if(logMINOR)
					Logger.minor(this, "Creating new grabber: "+requestGrabber+" for "+client+" from "+clientGrabber+" : "+prio+" : prio="+priorityClass+", rc="+rc);
				clientGrabber.addGrabber(client, requestGrabber);
			}
			requestGrabber.add(cr, req);
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

	public void reregisterAll(ClientRequester request, RandomSource random, ClientRequestScheduler lock) {
		SendableRequest[] reqs;
		synchronized(lock) {
			HashSet h = (HashSet) allRequestsByClientRequest.get(request);
			if(h == null) return;
			reqs = (SendableRequest[]) h.toArray(new SendableRequest[h.size()]);
		}
		
		for(int i=0;i<reqs.length;i++) {
			SendableRequest req = reqs[i];
			// Unregister from the RGA's, but keep the pendingKeys and cooldown queue data.
			req.unregister(true);
			// Then can do innerRegister() (not register()).
			innerRegister(req, random);
		}
	}

	public void succeeded(BaseSendableGet succeeded) {
		if(isInsertScheduler) return;
			if(logMINOR)
				Logger.minor(this, "Recording successful fetch from "+succeeded);
			recentSuccesses.add(succeeded);
			while(recentSuccesses.size() > 8)
				recentSuccesses.remove(0);
	}

	protected void removeFromAllRequestsByClientRequest(SendableRequest req, ClientRequester cr) {
			HashSet v = (HashSet) allRequestsByClientRequest.get(cr);
			if(v == null) {
				Logger.error(this, "No HashSet registered for "+cr);
			} else {
				boolean removed = v.remove(req);
				if(v.isEmpty())
					allRequestsByClientRequest.remove(cr);
				if(logMINOR) Logger.minor(this, (removed ? "" : "Not ") + "Removed from HashSet for "+cr+" which now has "+v.size()+" elements");
			}
	}

}
