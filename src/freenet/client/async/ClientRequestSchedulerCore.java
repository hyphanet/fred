/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashSet;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;
import com.db4o.types.Db4oList;
import com.db4o.types.Db4oMap;

import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.SortedVectorByNumber;

/**
 * @author toad
 * A persistent class that functions as the core of the ClientRequestScheduler.
 * Does not refer to any non-persistable classes as member variables: Node must always 
 * be passed in if we need to use it!
 */
class ClientRequestSchedulerCore {
	
	private static boolean logMINOR;
	/** Identifier in the database for the node we are attached to */
	private final long nodeDBHandle;
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	private final Db4oMap allRequestsByClientRequest;
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	private final SortedVectorByNumber[] priorities;
	// FIXME cooldown queue ????
	// Can we make the cooldown queue non-persistent? It refers to SendableGet's ... so
	// keeping it in memory may be a problem...
	/** All pending gets by key. Used to automatically satisfy pending requests when either the key is fetched by
	 * an overlapping request, or it is fetched by a request from another node. Operations on this are synchronized on
	 * itself. */
	private final Db4oMap /* <Key, SendableGet[]> */ pendingKeys;
	private final Db4oList /* <BaseSendableGet> */ recentSuccesses;

	/**
	 * Fetch a ClientRequestSchedulerCore from the database, or create a new one.
	 * @param node
	 * @param forInserts
	 * @param forSSKs
	 * @param selectorContainer
	 * @return
	 */
	public static ClientRequestSchedulerCore create(Node node, final boolean forInserts, final boolean forSSKs, ObjectContainer selectorContainer) {
		final long nodeDBHandle = node.nodeDBHandle;
		ObjectSet results = selectorContainer.query(new Predicate() {
			public boolean match(ClientRequestSchedulerCore core) {
				if(core.nodeDBHandle != nodeDBHandle) return false;
				if(core.isInsertScheduler != forInserts) return false;
				if(core.isSSKScheduler != forSSKs) return false;
				return true;
			}
		});
		ClientRequestSchedulerCore core;
		if(results.hasNext()) {
			core = (ClientRequestSchedulerCore) (results.next());
		} else {
			core = new ClientRequestSchedulerCore(node, forInserts, forSSKs, selectorContainer);
		}
		logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequestSchedulerCore.class);
		core.onStarted();
		return core;
	}

	ClientRequestSchedulerCore(Node node, boolean forInserts, boolean forSSKs, ObjectContainer selectorContainer) {
		this.nodeDBHandle = node.nodeDBHandle;
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		if(!isInsertScheduler)
			pendingKeys = selectorContainer.ext().collections().newHashMap(1024);
		else
			pendingKeys = null;
		allRequestsByClientRequest = selectorContainer.ext().collections().newHashMap(32);
		recentSuccesses = selectorContainer.ext().collections().newLinkedList();
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
	}

	private void onStarted() {
		pendingKeys.activationDepth(1);
		allRequestsByClientRequest.activationDepth(1);
	}
	
	/**
	 * Register a pending key to an already-registered request. This is necessary if we've
	 * already registered a SendableGet, but we later add some more keys to it.
	 */
	void addPendingKey(ClientKey key, SendableGet getter) {
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
	
	synchronized void innerRegister(SendableRequest req, RandomSource random) {
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
	
	/**
	 * Mangle the retry count.
	 * Below a certain number of attempts, we don't prefer one request to another just because
	 * it's been tried more times. The reason for this is to prevent floods of low-retry-count
	 * requests from starving other clients' requests which need to be retried. The other
	 * solution would be to sort by client before retry count, but that would be excessive 
	 * IMHO; we DO want to avoid rerequesting keys we've tried many times before.
	 */
	private int fixRetryCount(int retryCount) {
		return Math.max(0, retryCount-MIN_RETRY_COUNT);
	}

	synchronized void addToGrabArray(short priorityClass, int retryCount, int rc, Object client, ClientRequester cr, SendableRequest req, RandomSource random) {
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
			clientGrabber = new SectoredRandomGrabArrayWithInt(random, rc);
			prio.add(clientGrabber);
			if(logMINOR) Logger.minor(this, "Registering retry count "+rc+" with prioclass "+priorityClass+" on "+clientGrabber+" for "+prio);
		}
		// SectoredRandomGrabArrayWithInt and lower down have hierarchical locking and auto-remove.
		// To avoid a race condition it is essential to mirror that here.
		synchronized(clientGrabber) {
			// Request
			SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) clientGrabber.getGrabber(client);
			if(requestGrabber == null) {
				requestGrabber = new SectoredRandomGrabArrayWithObject(client, random);
				if(logMINOR)
					Logger.minor(this, "Creating new grabber: "+requestGrabber+" for "+client+" from "+clientGrabber+" : "+prio+" : prio="+priorityClass+", rc="+rc);
				clientGrabber.addGrabber(client, requestGrabber);
			}
			requestGrabber.add(cr, req);
		}
	}

	private int removeFirstAccordingToPriorities(boolean tryOfferedKeys, int fuzz, RandomSource random, OfferedKeysList[] offeredKeys){
		SortedVectorByNumber result = null;
		
		short iteration = 0, priority;
		// we loop to ensure we try every possibilities ( n + 1)
		//
		// PRIO will do 0,1,2,3,4,5,6,0
		// TWEAKED will do rand%6,0,1,2,3,4,5,6
		while(iteration++ < RequestStarter.NUMBER_OF_PRIORITY_CLASSES + 1){
			priority = fuzz<0 ? tweakedPrioritySelector[random.nextInt(tweakedPrioritySelector.length)] : prioritySelector[Math.abs(fuzz % prioritySelector.length)];
			result = priorities[priority];
			if((result != null) && 
					(!result.isEmpty()) || (tryOfferedKeys && !offeredKeys[priority].isEmpty())) {
				if(logMINOR) Logger.minor(this, "using priority : "+priority);
				return priority;
			}
			
			if(logMINOR) Logger.minor(this, "Priority "+priority+" is null (fuzz = "+fuzz+ ')');
			fuzz++;
		}
		
		//FIXME: implement NONE
		return -1;
	}
	
	// LOCKING: Life is a good deal simpler if we just synchronize on (this). 
	// We prevent a number of race conditions (e.g. adding a retry count and then another 
	// thread removes it cos its empty) ... and in addToGrabArray etc we already sync on this.
	// The worry is ... is there any nested locking outside of the hierarchy?
	synchronized SendableRequest removeFirst(int fuzz, RandomSource random, OfferedKeysList[] offeredKeys, RequestStarter starter) {
		// Priorities start at 0
		if(logMINOR) Logger.minor(this, "removeFirst()");
		boolean tryOfferedKeys = offeredKeys != null && random.nextBoolean();
		int choosenPriorityClass = removeFirstAccordingToPriorities(tryOfferedKeys, fuzz, random, offeredKeys);
		if(choosenPriorityClass == -1 && offeredKeys != null && !tryOfferedKeys) {
			tryOfferedKeys = true;
			choosenPriorityClass = removeFirstAccordingToPriorities(tryOfferedKeys, fuzz, random, offeredKeys);
		}
		if(choosenPriorityClass == -1) {
			if(logMINOR)
				Logger.minor(this, "Nothing to do");
			return null;
		}
		for(;choosenPriorityClass <= RequestStarter.MINIMUM_PRIORITY_CLASS;choosenPriorityClass++) {
			if(logMINOR) Logger.minor(this, "Using priority "+choosenPriorityClass);
		if(tryOfferedKeys) {
			if(offeredKeys[choosenPriorityClass].hasValidKeys(starter))
				return offeredKeys[choosenPriorityClass];
		}
		SortedVectorByNumber s = priorities[choosenPriorityClass];
		if(s != null){
			for(int retryIndex=0;retryIndex<s.count();retryIndex++) {
				SectoredRandomGrabArrayWithInt retryTracker = (SectoredRandomGrabArrayWithInt) s.getByIndex(retryIndex);
				if(retryTracker == null) {
					if(logMINOR) Logger.minor(this, "No retrycount's left");
					break;
				}
				while(true) {
					if(logMINOR)
						Logger.minor(this, "Got retry count tracker "+retryTracker);
					SendableRequest req = (SendableRequest) retryTracker.removeRandom(starter);
					if(retryTracker.isEmpty()) {
						if(logMINOR) Logger.minor(this, "Removing retrycount "+retryTracker.getNumber()+" : "+retryTracker);
						s.remove(retryTracker.getNumber());
						if(s.isEmpty()) {
							if(logMINOR) Logger.minor(this, "Should remove priority ");
						}
					}
					if(req == null) {
						if(logMINOR) Logger.minor(this, "No requests, adjusted retrycount "+retryTracker.getNumber()+" ("+retryTracker+ ')');
						break; // Try next retry count.
					} else if(req.getPriorityClass() != choosenPriorityClass) {
						// Reinsert it : shouldn't happen if we are calling reregisterAll,
						// maybe we should ask people to report that error if seen
						Logger.normal(this, "In wrong priority class: "+req+" (req.prio="+req.getPriorityClass()+" but chosen="+choosenPriorityClass+ ')');
						// Remove it.
						SectoredRandomGrabArrayWithObject clientGrabber = (SectoredRandomGrabArrayWithObject) retryTracker.getGrabber(req.getClient());
						if(clientGrabber != null) {
							RandomGrabArray baseRGA = (RandomGrabArray) clientGrabber.getGrabber(req.getClientRequest());
							if(baseRGA != null) {
								baseRGA.remove(req);
							} else {
								Logger.error(this, "Could not find base RGA for requestor "+req.getClientRequest()+" from "+clientGrabber);
							}
						} else {
							Logger.error(this, "Could not find client grabber for client "+req.getClient()+" from "+retryTracker);
						}
						innerRegister(req, random);
						continue; // Try the next one on this retry count.
					}
					
					SendableRequest altReq = null;
					synchronized(this) {
						if(!recentSuccesses.isEmpty()) {
							if(random.nextBoolean()) {
								altReq = (BaseSendableGet) recentSuccesses.remove(recentSuccesses.size()-1);
							}
						}
					}
						if(altReq != null && altReq.getPriorityClass() <= choosenPriorityClass && 
								fixRetryCount(altReq.getRetryCount()) <= retryTracker.getNumber()) {
							// Use the recent one instead
							if(logMINOR)
								Logger.minor(this, "Recently succeeded req "+altReq+" is better, using that, reregistering chosen "+req);
							innerRegister(req, random);
							req = altReq;
						} else {
							if(altReq != null) {
								synchronized(this) {
									recentSuccesses.add(altReq);
								}
								if(logMINOR)
									Logger.minor(this, "Chosen req "+req+" is better, reregistering recently succeeded "+altReq);
								innerRegister(altReq, random);
							}
						}
					
					if(logMINOR) Logger.debug(this, "removeFirst() returning "+req+" ("+retryTracker.getNumber()+", prio "+
							req.getPriorityClass()+", retries "+req.getRetryCount()+", client "+req.getClient()+", client-req "+req.getClientRequest()+ ')');
					ClientRequester cr = req.getClientRequest();
					if(req.canRemove()) {
						synchronized(this) {
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
						// Do not remove from the pendingKeys list.
						// Whether it is running a request, waiting to execute, or waiting on the
						// cooldown queue, ULPRs and backdoor coalescing should still be active.
					}
					if(logMINOR) Logger.minor(this, "removeFirst() returning "+req+" of "+req.getClientRequest());
					return req;
				}
			}
		}
		}
		if(logMINOR) Logger.minor(this, "No requests to run");
		return null;
	}
	
	public void reregisterAll(ClientRequester request, RandomSource random) {
		SendableRequest[] reqs;
		synchronized(this) {
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

	private static final short[] tweakedPrioritySelector = { 
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
		
		RequestStarter.UPDATE_PRIORITY_CLASS,
		RequestStarter.UPDATE_PRIORITY_CLASS, 
		RequestStarter.UPDATE_PRIORITY_CLASS, 
		RequestStarter.UPDATE_PRIORITY_CLASS,
		
		RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS,
		
		RequestStarter.PREFETCH_PRIORITY_CLASS, 
		RequestStarter.PREFETCH_PRIORITY_CLASS,
		
		RequestStarter.MINIMUM_PRIORITY_CLASS
	};
	private static final short[] prioritySelector = {
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.UPDATE_PRIORITY_CLASS,
		RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS,
		RequestStarter.PREFETCH_PRIORITY_CLASS,
		RequestStarter.MINIMUM_PRIORITY_CLASS
	};

	/** Minimum number of retries at which we start to hold it against a request.
	 * See the comments on fixRetryCount; we don't want many untried requests to prevent
	 * us from trying requests which have only been tried once (e.g. USK checkers), from 
	 * other clients (and we DO want retries to take precedence over client round robin IF 
	 * the request has been tried many times already). */
	private static final int MIN_RETRY_COUNT = 3;

	public void succeeded(BaseSendableGet succeeded) {
		if(isInsertScheduler) return;
		synchronized(this) {
			if(logMINOR)
				Logger.minor(this, "Recording successful fetch from "+succeeded);
			recentSuccesses.add(succeeded);
			while(recentSuccesses.size() > 8)
				recentSuccesses.remove(0);
		}
	}

	
}
