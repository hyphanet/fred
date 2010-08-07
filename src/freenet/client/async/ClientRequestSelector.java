package freenet.client.async;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.node.SendableRequestItem;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;
import freenet.support.SectoredRandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.SortedVectorByNumber;
import freenet.support.Logger.LogLevel;

/** Chooses requests from both CRSCore and CRSNP */
class ClientRequestSelector implements KeysFetchingLocally {
	
	final boolean isInsertScheduler;
	
	final ClientRequestScheduler sched;
	
	ClientRequestSelector(boolean isInsertScheduler, ClientRequestScheduler sched) {
		this.sched = sched;
		this.isInsertScheduler = isInsertScheduler;
		if(!isInsertScheduler) {
			keysFetching = new HashSet<Key>();
			runningTransientInserts = null;
			this.recentSuccesses = new ArrayList<RandomGrabArray>();
		} else {
			keysFetching = null;
			runningTransientInserts = new HashSet<RunningTransientInsert>();
		}
	}
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/**
	 * All Key's we are currently fetching. 
	 * Locally originated requests only, avoids some complications with HTL, 
	 * and also has the benefit that we can see stuff that's been scheduled on a SenderThread
	 * but that thread hasn't started yet. FIXME: Both issues can be avoided: first we'd get 
	 * rid of the SenderThread and start the requests directly and asynchronously, secondly
	 * we'd move this to node but only track keys we are fetching at max HTL.
	 * LOCKING: Always lock this LAST.
	 */
	private transient HashSet<Key> keysFetching;
	
	private static class RunningTransientInsert {
		
		final SendableInsert insert;
		final Object token;
		
		RunningTransientInsert(SendableInsert i, Object t) {
			insert = i;
			token = t;
		}
		
		@Override
		public int hashCode() {
			return insert.hashCode() ^ token.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if(!(o instanceof RunningTransientInsert)) return false;
			RunningTransientInsert r = (RunningTransientInsert) o;
			return r.insert == insert && (r.token == token || r.token.equals(token));
		}
		
	}
	
	private transient HashSet<RunningTransientInsert> runningTransientInserts;
	
	private transient List<RandomGrabArray> recentSuccesses;
	
	// We pass in the schedTransient to the next two methods so that we can select between either of them.
	
	private int removeFirstAccordingToPriorities(int fuzz, RandomSource random, ClientRequestSchedulerCore schedCore, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, short maxPrio, ObjectContainer container){
		SectoredRandomGrabArray result = null;
		
		short iteration = 0, priority;
		// we loop to ensure we try every possibilities ( n + 1)
		//
		// PRIO will do 0,1,2,3,4,5,6,0
		// TWEAKED will do rand%6,0,1,2,3,4,5,6
		while(iteration++ < RequestStarter.NUMBER_OF_PRIORITY_CLASSES + 1){
			priority = fuzz<0 ? tweakedPrioritySelector[random.nextInt(tweakedPrioritySelector.length)] : prioritySelector[Math.abs(fuzz % prioritySelector.length)];
			if(transientOnly || schedCore == null)
				result = null;
			else
				result = schedCore.newPriorities[priority];
			if(result == null)
				result = schedTransient.newPriorities[priority];
			if(priority > maxPrio) {
				fuzz++;
				continue; // Don't return because first round may be higher with soft scheduling
			}
			if(((result != null) && (!result.isEmpty()))) {
				if(logMINOR) Logger.minor(this, "using priority : "+priority);
				return priority;
			}
			
			if(logMINOR) Logger.minor(this, "Priority "+priority+" is null (fuzz = "+fuzz+ ')');
			fuzz++;
		}
		
		//FIXME: implement NONE
		return -1;
	}
	
	// LOCKING: ClientRequestScheduler locks on (this) before calling. 
	// We prevent a number of race conditions (e.g. adding a retry count and then another 
	// thread removes it cos its empty) ... and in addToGrabArray etc we already sync on this.
	// The worry is ... is there any nested locking outside of the hierarchy?
	ChosenBlock removeFirstTransient(int fuzz, RandomSource random, OfferedKeysList offeredKeys, RequestStarter starter, ClientRequestSchedulerNonPersistent schedTransient, short maxPrio, int retryCount, ClientContext context, ObjectContainer container) {
		// If a block is already running it will return null. Try to find a valid block in that case.
		for(int i=0;i<5;i++) {
			SendableRequest req = removeFirstInner(fuzz, random, offeredKeys, starter, null, schedTransient, true, false, maxPrio, retryCount, context, container);
			if(isInsertScheduler && req instanceof SendableGet) {
				IllegalStateException e = new IllegalStateException("removeFirstInner returned a SendableGet on an insert scheduler!!");
				req.internalError(e, sched, container, context, req.persistent());
				throw e;
			}
			ChosenBlock block = maybeMakeChosenRequest(req, container, context);
			if(block != null) return block;
		}
		return null;
	}
	
	private int ctr;
	
	public ChosenBlock maybeMakeChosenRequest(SendableRequest req, ObjectContainer container, ClientContext context) {
		if(req == null) return null;
		if(req.isEmpty(container) || req.isCancelled(container)) return null;
		SendableRequestItem token = req.chooseKey(this, req.persistent() ? container : null, context);
		if(token == null) {
			return null;
		} else {
			Key key;
			ClientKey ckey;
			if(isInsertScheduler) {
				key = null;
				ckey = null;
			} else {
				key = ((BaseSendableGet)req).getNodeKey(token, null);
				if(req instanceof SendableGet)
					ckey = ((SendableGet)req).getKey(token, null);
				else
					ckey = null;
			}
			ChosenBlock ret;
			assert(!req.persistent());
			if(key != null && key.getRoutingKey() == null)
				throw new NullPointerException();
			boolean localRequestOnly;
			boolean ignoreStore;
			boolean canWriteClientCache;
			boolean forkOnCacheable;
			if(req instanceof SendableGet) {
				SendableGet sg = (SendableGet) req;
				FetchContext ctx = sg.getContext();
				localRequestOnly = ctx.localRequestOnly;
				ignoreStore = ctx.ignoreStore;
				canWriteClientCache = ctx.canWriteClientCache;
				forkOnCacheable = false;
			} else {
				localRequestOnly = false;
				if(req instanceof SendableInsert) {
					canWriteClientCache = ((SendableInsert)req).canWriteClientCache(null);
					forkOnCacheable = ((SendableInsert)req).forkOnCacheable(null);
					localRequestOnly = ((SendableInsert)req).localRequestOnly(null);
				} else {
					canWriteClientCache = false;
					forkOnCacheable = Node.FORK_ON_CACHEABLE_DEFAULT;
					localRequestOnly = false;
				}
				ignoreStore = false;
			}
			ret = new TransientChosenBlock(req, token, key, ckey, localRequestOnly, ignoreStore, canWriteClientCache, forkOnCacheable, sched);
			return ret;
		}
	}

	SendableRequest removeFirstInner(int fuzz, RandomSource random, OfferedKeysList offeredKeys, RequestStarter starter, ClientRequestSchedulerCore schedCore, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, boolean notTransient, short maxPrio, int retryCount, ClientContext context, ObjectContainer container) {
		// Priorities start at 0
		if(logMINOR) Logger.minor(this, "removeFirst()");
		if(schedCore == null) transientOnly = true;
		if(transientOnly && notTransient) {
			Logger.error(this, "Not transient but no core");
			return null;
		}
		boolean tryOfferedKeys = offeredKeys != null && (!notTransient) && random.nextBoolean();
		if(tryOfferedKeys) {
			if(offeredKeys.hasValidKeys(this, null, context))
				return offeredKeys;
		}
		int choosenPriorityClass = removeFirstAccordingToPriorities(fuzz, random, schedCore, schedTransient, transientOnly, maxPrio, container);
		if(choosenPriorityClass == -1) {
			if((!notTransient) && !tryOfferedKeys) {
				if(offeredKeys != null && offeredKeys.hasValidKeys(this, null, context))
					return offeredKeys;
			}
			if(logMINOR)
				Logger.minor(this, "Nothing to do");
			return null;
		}
		if(maxPrio >= RequestStarter.MINIMUM_PRIORITY_CLASS)
			maxPrio = RequestStarter.MINIMUM_PRIORITY_CLASS;
outer:	for(;choosenPriorityClass <= maxPrio;choosenPriorityClass++) {
			if(logMINOR) Logger.minor(this, "Using priority "+choosenPriorityClass);
			SectoredRandomGrabArray perm = null;
			if(!transientOnly)
				perm = schedCore.newPriorities[choosenPriorityClass];
			SectoredRandomGrabArray trans = null;
			if(!notTransient)
				trans = schedTransient.newPriorities[choosenPriorityClass];
			if(perm == null && trans == null) {
				if(logMINOR) Logger.minor(this, "No requests to run: chosen priority empty");
				continue; // Try next priority
			}
			boolean triedPerm = false;
			boolean triedTrans = false;
			while(true) {
				SectoredRandomGrabArray chosenTracker = null;
				// If we can't find anything on perm (on the previous loop), try trans, and vice versa
				if(triedTrans) trans = null;
				if(triedPerm) perm = null;
				if(perm == null && trans == null) continue outer;
				else if(perm == null && trans != null) chosenTracker = trans;
				else if(perm != null && trans == null) chosenTracker = perm;
				else {
					container.activate(perm, 1);
					int permSize = perm.size();
					int transSize = trans.size();
					if(random.nextInt(permSize + transSize) > permSize) {
						chosenTracker = perm;
						triedPerm = true;
					} else {
						chosenTracker = trans;
						triedTrans = true;
					}
				}
				
				if(logMINOR)
					Logger.minor(this, "Got priority tracker "+chosenTracker);
				SendableRequest req = (SendableRequest) chosenTracker.removeRandom(starter, container, context);
				if(req == null) {
					if(logMINOR) Logger.minor(this, "No requests, priority "+choosenPriorityClass+" persistent = "+chosenTracker.persistent());
					continue outer; // Try next priority.
				}
				if(chosenTracker.persistent())
					container.activate(req, 1); // FIXME
				if(req.persistent() != chosenTracker.persistent()) {
					Logger.error(this, "Request.persistent()="+req.persistent()+" but is in the queue for persistent="+chosenTracker.persistent()+" for "+req);
					// FIXME fix it
				}
				if(req.getPriorityClass(container) != choosenPriorityClass) {
					// Reinsert it : shouldn't happen if we are calling reregisterAll,
					// maybe we should ask people to report that error if seen
					Logger.normal(this, "In wrong priority class: "+req+" (req.prio="+req.getPriorityClass(container)+" but chosen="+choosenPriorityClass+ ')');
					// Remove it.
					SectoredRandomGrabArrayWithObject clientGrabber = (SectoredRandomGrabArrayWithObject) chosenTracker.getGrabber(req.getClient(container));
					if(clientGrabber != null) {
						if(chosenTracker.persistent())
							container.activate(clientGrabber, 1);
						RandomGrabArray baseRGA = (RandomGrabArray) clientGrabber.getGrabber(req.getClientRequest());
						if(baseRGA != null) {
							if(chosenTracker.persistent())
								container.activate(baseRGA, 1);
							baseRGA.remove(req, container);
						} else {
							// Okay, it's been removed already. Cool.
						}
					} else {
						Logger.error(this, "Could not find client grabber for client "+req.getClient(container)+" from "+chosenTracker);
					}
					if(req.persistent())
						schedCore.innerRegister(req, random, container, null);
					else
						schedTransient.innerRegister(req, random, container, null);
					continue; // Try the rest of the priority.
				}
				
				// Check recentSuccesses
				/** Choose a recently succeeded request.
				 * 50% chance of using a recently succeeded request, if there is one.
				 * For transient requests, we keep a list of recently succeeded BaseSendableGet's,
				 * because transient requests are chosen individually.
				 * But for persistent requests, we keep a list of RandomGrabArray's, because
				 * persistent requests are chosen a whole SendableRequest at a time.
				 * 
				 * FIXME: Only replaces persistent requests with persistent requests (of similar priority and retry count), or transient with transient.
				 * Probably this is acceptable.
				 */
				if(!req.persistent() && !isInsertScheduler) {
					List<BaseSendableGet> recent = schedTransient.recentSuccesses;
					BaseSendableGet altReq = null;
					synchronized(recent) {
						if(!recent.isEmpty()) {
							if(random.nextBoolean()) {
								altReq = recent.remove(recent.size()-1);
							}
						}
					}
					if(altReq != null && (altReq.isCancelled(container) || altReq.isEmpty(container))) {
						if(logMINOR)
							Logger.minor(this, "Ignoring cancelled recently succeeded item "+altReq);
						altReq = null;
					}
					if (altReq != null && altReq != req) {
						int prio = altReq.getPriorityClass(container);
						if(prio < choosenPriorityClass) {
							// Use the recent one instead
							if(logMINOR)
								Logger.minor(this, "Recently succeeded (transient) req "+altReq+" (prio="+altReq.getPriorityClass(container)+" retry count "+altReq.getRetryCount()+") is better than "+req+" (prio="+req.getPriorityClass(container)+" retry "+req.getRetryCount()+"), using that");
							// Don't need to reregister, because removeRandom doesn't actually remove!
							req = altReq;
						} else {
							// Don't use the recent one
							if(logMINOR)
								Logger.minor(this, "Chosen req "+req+" is better, reregistering recently succeeded "+altReq);
							synchronized(recent) {
								recent.add(altReq);
							}
						}
					}
				} else if(!isInsertScheduler) {
					RandomGrabArray altRGA = null;
					synchronized(recentSuccesses) {
						if(!(recentSuccesses.isEmpty() || random.nextBoolean())) {
							altRGA = recentSuccesses.remove(recentSuccesses.size()-1);
						}
					}
					if(altRGA != null) {
						container.activate(altRGA, 1);
						if(container.ext().isStored(altRGA) && !altRGA.isEmpty()) {
							if(logMINOR)
								Logger.minor(this, "Maybe using recently succeeded item from "+altRGA);
							SendableRequest altReq = (SendableRequest) altRGA.removeRandom(starter, container, context);
							if(altReq != null) {
								container.activate(altReq, 1);
								int prio = altReq.getPriorityClass(container);
								if((prio < choosenPriorityClass)
										&& !altReq.isEmpty(container) && altReq != req) {
									// Use the recent one instead
									if(logMINOR)
										Logger.minor(this, "Recently succeeded (persistent) req "+altReq+" (prio="+altReq.getPriorityClass(container)+" retry count "+altReq.getRetryCount()+") is better than "+req+" (prio="+req.getPriorityClass(container)+" retry "+req.getRetryCount()+"), using that");
									// Don't need to reregister, because removeRandom doesn't actually remove!
									req = altReq;
								} else if(altReq != null) {
									if(logMINOR)
										Logger.minor(this, "Chosen (persistent) req "+req+" is better, reregistering recently succeeded "+altRGA+" for "+altReq);
									synchronized(recentSuccesses) {
										recentSuccesses.add(altRGA);
									}
								}
							}
						} else {
							container.deactivate(altRGA, 1);
						}
					}
				}
				
				// Now we have chosen a request.
				if(logMINOR) Logger.minor(this, "removeFirst() returning "+req+" (prio "+
						req.getPriorityClass(container)+", retries "+req.getRetryCount()+", client "+req.getClient(container)+", client-req "+req.getClientRequest()+ ')');
				if(logMINOR) Logger.minor(this, "removeFirst() returning "+req+" of "+req.getClientRequest());
				return req;
				
			}
		}
		if(logMINOR) Logger.minor(this, "No requests to run");
		return null;
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

	/**
	 * @return True unless the key was already present.
	 */
	public boolean addToFetching(Key key) {
		synchronized(keysFetching) {
			boolean retval = keysFetching.add(key);
			if(!retval) {
				Logger.normal(this, "Already in keysFetching: "+key);
			} else {
				if(logMINOR)
					Logger.minor(this, "Added to keysFetching: "+key);
			}
			return retval;
		}
	}
	
	public boolean hasKey(Key key) {
		if(keysFetching == null) {
			throw new NullPointerException();
		}
		synchronized(keysFetching) {
			return keysFetching.contains(key);
		}
	}

	public void removeFetchingKey(final Key key) {
		if(logMINOR)
			Logger.minor(this, "Removing from keysFetching: "+key);
		if(key != null) {
			synchronized(keysFetching) {
				keysFetching.remove(key);
			}
		}
	}

	public boolean hasTransientInsert(SendableInsert insert, Object token) {
		RunningTransientInsert tmp = new RunningTransientInsert(insert, token);
		synchronized(runningTransientInserts) {
			return runningTransientInserts.contains(tmp);
		}
	}

	public boolean addTransientInsertFetching(SendableInsert insert, Object token) {
		RunningTransientInsert tmp = new RunningTransientInsert(insert, token);
		synchronized(runningTransientInserts) {
			boolean retval = runningTransientInserts.add(tmp);
			if(!retval) {
				Logger.normal(this, "Already in runningTransientInserts: "+insert+" : "+token);
			} else {
				if(logMINOR)
					Logger.minor(this, "Added to runningTransientInserts: "+insert+" : "+token);
			}
			return retval;
		}
	}
	
	public void removeTransientInsertFetching(SendableInsert insert, Object token) {
		RunningTransientInsert tmp = new RunningTransientInsert(insert, token);
		if(logMINOR)
			Logger.minor(this, "Removing from runningTransientInserts: "+insert+" : "+token);
		synchronized(runningTransientInserts) {
			runningTransientInserts.remove(tmp);
		}
	}

	public void succeeded(BaseSendableGet succeeded, ObjectContainer container) {
		RandomGrabArray array = succeeded.getParentGrabArray();
		container.activate(array, 1);
		if(array == null) return; // Unregistered already?
		synchronized(recentSuccesses) {
			if(recentSuccesses.contains(array)) return;
			recentSuccesses.add(array);
			while(recentSuccesses.size() > 8)
				recentSuccesses.remove(0);
		}
	}
	
}
