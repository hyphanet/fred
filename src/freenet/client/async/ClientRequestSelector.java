package freenet.client.async;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.Node;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestItemKey;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.RandomGrabArray;
import freenet.support.RemoveRandom.RemoveRandomReturn;
import freenet.support.SectoredRandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.TimeUtil;

/** Chooses requests from both CRSCore and CRSNP */
class ClientRequestSelector implements KeysFetchingLocally {
	
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final boolean isRTScheduler;
	
	final ClientRequestScheduler sched;
	
	/**
     * The base of the tree.
     * array (by priority) -> // one element per possible priority
     * SectoredRandomGrabArray's // round-robin by RequestClient, then by SendableRequest
     * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
     */
    protected SectoredRandomGrabArray[] newPriorities;
	
	ClientRequestSelector(boolean isInsertScheduler, boolean isSSKScheduler, boolean isRTScheduler, ClientRequestScheduler sched) {
		this.sched = sched;
		this.isInsertScheduler = isInsertScheduler;
		this.isSSKScheduler = isSSKScheduler;
		this.isRTScheduler = isRTScheduler;
		if(!isInsertScheduler) {
			keysFetching = new HashSet<Key>();
			transientRequestsWaitingForKeysFetching = new HashMap<Key, WeakReference<BaseSendableGet>[]>();
			runningTransientInserts = null;
			this.recentSuccesses = new ArrayDeque<RandomGrabArray>();
		} else {
			keysFetching = null;
			runningTransientInserts = new HashSet<RunningTransientInsert>();
			this.recentSuccesses = null;
		}
		newPriorities = new SectoredRandomGrabArray[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
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
	
	private transient HashMap<Key, WeakReference<BaseSendableGet>[]> transientRequestsWaitingForKeysFetching;
	
	private static class RunningTransientInsert {
		
		final SendableInsert insert;
		final SendableRequestItemKey token;
		
		RunningTransientInsert(SendableInsert i, SendableRequestItemKey t) {
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
	
	private transient final HashSet<RunningTransientInsert> runningTransientInserts;
	
	private transient final Deque<RandomGrabArray> recentSuccesses;
	
	// We pass in the schedTransient to the next two methods so that we can select between either of them.
	
	private long removeFirstAccordingToPriorities(int fuzz, RandomSource random, ClientRequestSchedulerCore schedCore, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, short maxPrio, ObjectContainer container, ClientContext context, long now){
		SectoredRandomGrabArray result = null;
		
		long wakeupTime = Long.MAX_VALUE;
		
		short iteration = 0, priority;
		// we loop to ensure we try every possibilities ( n + 1)
		//
		// PRIO will do 0,1,2,3,4,5,6,0
		// TWEAKED will do rand%6,0,1,2,3,4,5,6
		while(iteration++ < RequestStarter.NUMBER_OF_PRIORITY_CLASSES + 1){
			boolean persistent = false;
			priority = fuzz<0 ? tweakedPrioritySelector[random.nextInt(tweakedPrioritySelector.length)] : prioritySelector[Math.abs(fuzz % prioritySelector.length)];
			result = newPriorities[priority];
			if(result != null) {
			    long cooldownTime = context.cooldownTracker.getCachedWakeup(result, now);
			    if(cooldownTime > 0) {
			        if(cooldownTime < wakeupTime) wakeupTime = cooldownTime;
			        if(logMINOR) {
			            if(cooldownTime == Long.MAX_VALUE)
			                Logger.minor(this, "Priority "+priority+" (persistent) is waiting until a request finishes or is empty");
			            else
			                Logger.minor(this, "Priority "+priority+" (persistent) is in cooldown for another "+(cooldownTime - now)+" "+TimeUtil.formatTime(cooldownTime - now));
			        }
			        result = null;
				}
			}
			if(priority > maxPrio) {
				fuzz++;
				continue; // Don't return because first round may be higher with soft scheduling
			}
			if(((result != null) && (!result.isEmpty(persistent ? container : null)))) {
				if(logMINOR) Logger.minor(this, "using priority : "+priority);
				return priority;
			}
			
			if(logMINOR) Logger.minor(this, "Priority "+priority+" is null (fuzz = "+fuzz+ ')');
			fuzz++;
		}
		
		//FIXME: implement NONE
		return wakeupTime;
	}
	
	// LOCKING: ClientRequestScheduler locks on (this) before calling. 
	// We prevent a number of race conditions (e.g. adding a retry count and then another 
	// thread removes it cos its empty) ... and in addToGrabArray etc we already sync on this.
	// The worry is ... is there any nested locking outside of the hierarchy?
	ChosenBlock removeFirstTransient(int fuzz, RandomSource random, OfferedKeysList offeredKeys, RequestStarter starter, ClientRequestSchedulerNonPersistent schedTransient, short maxPrio, boolean realTime, ClientContext context, ObjectContainer container) {
		// If a block is already running it will return null. Try to find a valid block in that case.
		long now = System.currentTimeMillis();
		for(int i=0;i<5;i++) {
			// Must synchronize on scheduler to avoid problems with cooldown queue. See notes on CooldownTracker.clearCachedWakeup, which also applies to other cooldown operations.
			SelectorReturn r;
			synchronized(sched) {
				r = removeFirstInner(fuzz, random, offeredKeys, starter, null, schedTransient, true, false, maxPrio, realTime, context, container, now);
			}
			SendableRequest req = null;
			if(r != null && r.req != null) req = r.req;
			if(req == null) continue;
			if(isInsertScheduler && req instanceof SendableGet) {
				IllegalStateException e = new IllegalStateException("removeFirstInner returned a SendableGet on an insert scheduler!!");
				req.internalError(e, sched, container, context, req.persistent());
				throw e;
			}
			ChosenBlock block = maybeMakeChosenRequest(req, container, context, now);
			if(block != null) return block;
		}
		return null;
	}
	
	public ChosenBlock maybeMakeChosenRequest(SendableRequest req, ObjectContainer container, ClientContext context, long now) {
		if(req == null) return null;
		if(req.isCancelled(container)) {
			if(logMINOR) Logger.minor(this, "Request is cancelled: "+req);
			return null;
		}
		if(req.getCooldownTime(container, context, now) != 0) {
			if(logMINOR) Logger.minor(this, "Request is in cooldown: "+req);
			return null;
		}
		SendableRequestItem token = req.chooseKey(this, req.persistent() ? container : null, context);
		if(token == null) {
			if(logMINOR) Logger.minor(this, "Choose key returned null: "+req);
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
			boolean realTimeFlag;
			if(req instanceof SendableGet) {
				SendableGet sg = (SendableGet) req;
				FetchContext ctx = sg.getContext(container);
				localRequestOnly = ctx.localRequestOnly;
				ignoreStore = ctx.ignoreStore;
				canWriteClientCache = ctx.canWriteClientCache;
				realTimeFlag = sg.realTimeFlag();
				forkOnCacheable = false;
			} else {
				localRequestOnly = false;
				if(req instanceof SendableInsert) {
					canWriteClientCache = ((SendableInsert)req).canWriteClientCache(null);
					forkOnCacheable = ((SendableInsert)req).forkOnCacheable(null);
					localRequestOnly = ((SendableInsert)req).localRequestOnly(null);
					realTimeFlag = ((SendableInsert)req).realTimeFlag();
				} else {
					canWriteClientCache = false;
					forkOnCacheable = Node.FORK_ON_CACHEABLE_DEFAULT;
					localRequestOnly = false;
					realTimeFlag = false;
				}
				ignoreStore = false;
			}
			ret = new TransientChosenBlock(req, token, key, ckey, localRequestOnly, ignoreStore, canWriteClientCache, forkOnCacheable, realTimeFlag, sched);
			if(logMINOR) Logger.minor(this, "Created "+ret+" for "+req);
			return ret;
		}
	}

	public class SelectorReturn {
		public final SendableRequest req;
		public final long wakeupTime;
		SelectorReturn(SendableRequest req) {
			this.req = req;
			this.wakeupTime = -1;
		}
		SelectorReturn(long wakeupTime) {
			this.wakeupTime = wakeupTime;
			this.req = null;
		}
	}
	
	SelectorReturn removeFirstInner(int fuzz, RandomSource random, OfferedKeysList offeredKeys, RequestStarter starter, ClientRequestSchedulerCore schedCore, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, boolean notTransient, short maxPrio, boolean realTime, ClientContext context, ObjectContainer container, long now) {
		// Priorities start at 0
		if(logMINOR) Logger.minor(this, "removeFirst()");
		if(schedCore == null) transientOnly = true;
		if(transientOnly && notTransient) {
			Logger.error(this, "Not transient but no core");
			return null;
		}
		boolean tryOfferedKeys = offeredKeys != null && (!notTransient) && random.nextBoolean();
		if(tryOfferedKeys) {
			if(offeredKeys.getCooldownTime(container, context, now) == 0)
				return new SelectorReturn(offeredKeys);
		}
		long l = removeFirstAccordingToPriorities(fuzz, random, schedCore, schedTransient, transientOnly, maxPrio, container, context, now);
		if(l > Integer.MAX_VALUE) {
			if(logMINOR) Logger.minor(this, "No priority available for the next "+TimeUtil.formatTime(l - now));
			return null;
		}
		int choosenPriorityClass = (int)l;
		if(choosenPriorityClass == -1) {
			if((!notTransient) && !tryOfferedKeys) {
				if(offeredKeys != null && offeredKeys.getCooldownTime(container, context, now) == 0)
					return new SelectorReturn(offeredKeys);
			}
			if(logMINOR)
				Logger.minor(this, "Nothing to do");
			return null;
		}
		long wakeupTime = Long.MAX_VALUE;
		if(maxPrio >= RequestStarter.MINIMUM_PRIORITY_CLASS)
			maxPrio = RequestStarter.MINIMUM_PRIORITY_CLASS;
outer:	for(;choosenPriorityClass <= maxPrio;choosenPriorityClass++) {
			if(logMINOR) Logger.minor(this, "Using priority "+choosenPriorityClass);
			SectoredRandomGrabArray chosenTracker = newPriorities[choosenPriorityClass];
			if(chosenTracker == null) {
				if(logMINOR) Logger.minor(this, "No requests to run: chosen priority empty");
				continue; // Try next priority
			}
			while(true) {
			    long cooldownTime = context.cooldownTracker.getCachedWakeup(chosenTracker, now);
			    if(cooldownTime > 0) {
			        if(cooldownTime < wakeupTime) wakeupTime = cooldownTime;
			        Logger.normal(this, "Priority "+choosenPriorityClass+" is in cooldown for another "+(cooldownTime - now)+" "+TimeUtil.formatTime(cooldownTime - now));
			        continue outer;
				}
				
				if(logMINOR)
					Logger.minor(this, "Got priority tracker "+chosenTracker);
				RemoveRandomReturn val = chosenTracker.removeRandom(starter, null, context, now);
				SendableRequest req;
				if(val == null) {
					Logger.normal(this, "Priority "+choosenPriorityClass+" returned null - nothing to schedule, should remove priority");
					continue;
				} else if(val.item == null) {
					if(val.wakeupTime == -1)
						Logger.normal(this, "Priority "+choosenPriorityClass+" returned cooldown time of -1 - nothing to schedule, should remove priority");
					else {
						Logger.normal(this, "Priority "+choosenPriorityClass+" returned cooldown time of "+(val.wakeupTime - now)+" = "+TimeUtil.formatTime(val.wakeupTime - now));
						if(val.wakeupTime > 0 && val.wakeupTime < wakeupTime)
							wakeupTime = val.wakeupTime;
					}
					continue;
				} else {
					req = (SendableRequest) val.item;
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
							// Must synchronize on scheduler to avoid nasty race conditions with cooldown.
							synchronized(sched) {
								baseRGA.remove(req, container, context);
							}
						} else {
							// Okay, it's been removed already. Cool.
						}
					} else {
						Logger.error(this, "Could not find client grabber for client "+req.getClient(container)+" from "+chosenTracker);
					}
					if(req.persistent())
						schedCore.innerRegister(req, container, context, null);
					else
						schedTransient.innerRegister(req, container, context, null);
					continue;
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
					Deque<BaseSendableGet> recent = schedTransient.recentSuccesses;
					BaseSendableGet altReq = null;
					synchronized(recent) {
						if(!recent.isEmpty()) {
							if(random.nextBoolean()) {
								altReq = recent.poll();
							}
						}
					}
					if(altReq != null && (altReq.isCancelled(container))) {
						if(logMINOR)
							Logger.minor(this, "Ignoring cancelled recently succeeded item "+altReq);
						altReq = null;
					}
					if(altReq != null && (l = altReq.getCooldownTime(container, context, now)) != 0) {
						if(logMINOR) {
							Logger.minor(this, "Ignoring recently succeeded item, cooldown time = "+l+((l > 0) ? " ("+TimeUtil.formatTime(l - now)+")" : ""));
							altReq = null;
						}
					}
					if (altReq != null && altReq != req) {
						int prio = altReq.getPriorityClass(container);
						if(prio <= choosenPriorityClass) {
							// Use the recent one instead
							if(logMINOR)
								Logger.minor(this, "Recently succeeded (transient) req "+altReq+" (prio="+altReq.getPriorityClass(container)+") is better than "+req+" (prio="+req.getPriorityClass(container)+"), using that");
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
							altRGA = recentSuccesses.removeLast();
						}
					}
					if(altRGA != null) {
						container.activate(altRGA, 1);
						SendableRequest altReq = null;
						if(container.ext().isStored(altRGA) && !altRGA.isEmpty(container)) {
							if(logMINOR)
								Logger.minor(this, "Maybe using recently succeeded item from "+altRGA);
							val = altRGA.removeRandom(starter, container, context, now);
							if(val != null) {
								if(val.item == null) {
									if(logMINOR) Logger.minor(this, "Ignoring recently succeeded item, removeRandom returned cooldown time "+val.wakeupTime+((val.wakeupTime > 0) ? " ("+TimeUtil.formatTime(val.wakeupTime - now)+")" : ""));
								} else {
									altReq = (SendableRequest) val.item;
								}
							}
							if(altReq != null && altReq != req) {
								container.activate(altReq, 1);
								int prio = altReq.getPriorityClass(container);
								boolean useRecent = false;
								if(prio <= choosenPriorityClass) {
									if(altReq.getCooldownTime(container, context, now) != 0)
										useRecent = true;
								}
								if(useRecent) {
									// Use the recent one instead
									if(logMINOR)
										Logger.minor(this, "Recently succeeded (persistent) req "+altReq+" (prio="+altReq.getPriorityClass(container)+") is better than "+req+" (prio="+req.getPriorityClass(container)+"), using that");
									// Don't need to reregister, because removeRandom doesn't actually remove!
									req = altReq;
								} else {
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
						req.getPriorityClass(container)+", client "+req.getClient(container)+", client-req "+req.getClientRequest()+ ')');
				if(logMINOR) Logger.minor(this, "removeFirst() returning "+req+" of "+req.getClientRequest());
				assert(req.realTimeFlag() == realTime);
				return new SelectorReturn(req);
				
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
	
	@Override
	@SuppressWarnings("unchecked")
	public boolean hasKey(Key key, BaseSendableGet getterWaiting) {
		if(keysFetching == null) {
			throw new NullPointerException();
		}
		synchronized(keysFetching) {
			boolean ret = keysFetching.contains(key);
			if(!ret) return ret;
			// It is being fetched. Add the BaseSendableGet to the wait list so it gets woken up when the request finishes.
			if(getterWaiting != null) {
			    WeakReference<BaseSendableGet>[] waiting = transientRequestsWaitingForKeysFetching.get(key);
			    if(waiting == null) {
			        transientRequestsWaitingForKeysFetching.put(key, new WeakReference[] { new WeakReference<BaseSendableGet>(getterWaiting) });
			    } else {
			        for(WeakReference<BaseSendableGet> ref : waiting) {
			            if(ref.get() == getterWaiting) return true;
			        }
			        WeakReference<BaseSendableGet>[] newWaiting = Arrays.copyOf(waiting, waiting.length+1);
			        newWaiting[waiting.length] = new WeakReference<BaseSendableGet>(getterWaiting);
			        transientRequestsWaitingForKeysFetching.put(key, newWaiting);
			    }
			}
			return true;
		}
	}

	/** LOCKING: Caller should hold as few locks as possible */ 
	public void removeFetchingKey(final Key key) {
		WeakReference<BaseSendableGet>[] transientWaiting;
		if(logMINOR)
			Logger.minor(this, "Removing from keysFetching: "+key);
		if(key != null) {
			synchronized(keysFetching) {
				keysFetching.remove(key);
				transientWaiting = this.transientRequestsWaitingForKeysFetching.remove(key);
			}
			if(transientWaiting != null) {
				CooldownTracker tracker = sched.clientContext.cooldownTracker;
				if(transientWaiting != null) {
					for(WeakReference<BaseSendableGet> ref : transientWaiting) {
						BaseSendableGet get = ref.get();
						if(get == null) continue;
						synchronized(sched) {
							tracker.clearCachedWakeup(get);
						}
					}
				}
			}
		}
	}

	@Override
	public boolean hasTransientInsert(SendableInsert insert, SendableRequestItemKey token) {
		RunningTransientInsert tmp = new RunningTransientInsert(insert, token);
		synchronized(runningTransientInserts) {
			return runningTransientInserts.contains(tmp);
		}
	}

	public boolean addTransientInsertFetching(SendableInsert insert, SendableRequestItemKey token) {
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
	
	public void removeTransientInsertFetching(SendableInsert insert, SendableRequestItemKey token) {
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
			// ArrayDeque loves pow-of-2 sizes, trim before add
			while(recentSuccesses.size() >= 8)
				recentSuccesses.pollFirst();
			recentSuccesses.add(array);
		}
	}

	@Override
	public long checkRecentlyFailed(Key key, boolean realTime) {
		Node node = sched.getNode();
		return node.clientCore.checkRecentlyFailed(key, realTime);
	}
	
	   /** Add a request (or insert) to the request selection tree.
     * @param priorityClass The priority of the request.
     * @param client Label object indicating which larger group of requests this request belongs to
     * (e.g. the global queue, or an FCP client), and whether it is persistent.
     * @param cr The high-level request that this single block request is part of. E.g. a fetch for 
     * a single key may download many blocks in a splitfile; an insert for a large freesite is 
     * considered a single @see ClientRequester.
     * @param req A single SendableRequest object which is one or more low-level requests. E.g. it 
     * can be an insert of a single block, or it can be a request or insert for a single segment 
     * within a splitfile. 
     * @param container The database handle, if the request is persistent, in which case this will
     * be a ClientRequestSchedulerCore. If so, this method must be called on the database thread.
     * @param context The client context object, which contains links to all the important objects
     * that are not persisted in the database, e.g. executors, temporary filename generator, etc.
     */
    void addToGrabArray(short priorityClass, RequestClient client, ClientRequester cr, SendableRequest req, ObjectContainer container, ClientContext context) {
        if((priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS) || (priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS))
            throw new IllegalStateException("Invalid priority: "+priorityClass+" - range is "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" (most important) to "+RequestStarter.MINIMUM_PRIORITY_CLASS+" (least important)");
        // Client
        synchronized(this) {
            SectoredRandomGrabArray clientGrabber = newPriorities[priorityClass];
            if(clientGrabber == null) {
                clientGrabber = new SectoredRandomGrabArray(false, container, null);
                newPriorities[priorityClass] = clientGrabber;
                if(logMINOR) Logger.minor(this, "Registering client tracker for priority "+priorityClass+" : "+clientGrabber);
            }
            // SectoredRandomGrabArrayWithInt and lower down have hierarchical locking and auto-remove.
            // To avoid a race condition it is essential to mirror that here.
            synchronized(clientGrabber) {
                // Request
                SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) clientGrabber.getGrabber(client);
                if(requestGrabber == null) {
                    requestGrabber = new SectoredRandomGrabArrayWithObject(client, false, container, clientGrabber);
                    if(logMINOR)
                        Logger.minor(this, "Creating new grabber: "+requestGrabber+" for "+client+" from "+clientGrabber+" : prio="+priorityClass);
                    clientGrabber.addGrabber(client, requestGrabber, container, context);
                    // FIXME unnecessary as it knows its parent and addGrabber() will call it???
                    context.cooldownTracker.clearCachedWakeup(clientGrabber);
                }
                requestGrabber.add(cr, req, container, context);
            }
        }
        sched.wakeStarter();
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
    

	
}
