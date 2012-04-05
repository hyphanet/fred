package freenet.client.async;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
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
import freenet.support.Logger.LogLevel;
import freenet.support.RandomGrabArray;
import freenet.support.RemoveRandom.RemoveRandomReturn;
import freenet.support.SectoredRandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.TimeUtil;

/** Chooses requests from both CRSCore and CRSNP */
class ClientRequestSelector implements KeysFetchingLocally {
	
	final boolean isInsertScheduler;
	
	final ClientRequestScheduler sched;
	
	ClientRequestSelector(boolean isInsertScheduler, ClientRequestScheduler sched) {
		this.sched = sched;
		this.isInsertScheduler = isInsertScheduler;
		if(!isInsertScheduler) {
			keysFetching = new HashSet<Key>();
			persistentRequestsWaitingForKeysFetching = new HashMap<Key, Long[]>();
			transientRequestsWaitingForKeysFetching = new HashMap<Key, WeakReference<BaseSendableGet>[]>();
			runningTransientInserts = null;
			this.recentSuccesses = new ArrayList<RandomGrabArray>();
		} else {
			keysFetching = null;
			runningTransientInserts = new HashSet<RunningTransientInsert>();
			this.recentSuccesses = null;
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
	
	private transient HashMap<Key, Long[]> persistentRequestsWaitingForKeysFetching;
	private transient HashMap<Key, WeakReference<BaseSendableGet>[]> transientRequestsWaitingForKeysFetching;
	
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
	
	private transient final HashSet<RunningTransientInsert> runningTransientInserts;
	
	private transient final List<RandomGrabArray> recentSuccesses;
	
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
			if(transientOnly || schedCore == null)
				result = null;
			else {
				result = schedCore.newPriorities[priority];
				if(result != null) {
					long cooldownTime = context.cooldownTracker.getCachedWakeup(result, true, container, now);
					if(cooldownTime > 0) {
						if(cooldownTime < wakeupTime) wakeupTime = cooldownTime;
						if(logMINOR) {
							if(cooldownTime == Long.MAX_VALUE)
								Logger.minor(this, "Priority "+priority+" (persistent) is waiting until a request finishes or is empty");
							else
								Logger.minor(this, "Priority "+priority+" (persistent) is in cooldown for another "+(cooldownTime - now)+" "+TimeUtil.formatTime(cooldownTime - now));
						}
						result = null;
					} else {
						container.activate(result, 1);
						persistent = true;
					}
				}
			}
			if(result == null) {
				result = schedTransient.newPriorities[priority];
				if(result != null) {
					long cooldownTime = context.cooldownTracker.getCachedWakeup(result, false, container, now);
					if(cooldownTime > 0) {
						if(cooldownTime < wakeupTime) wakeupTime = cooldownTime;
						if(logMINOR) {
							if(cooldownTime == Long.MAX_VALUE)
								Logger.minor(this, "Priority "+priority+" (transient) is waiting until a request finishes or is empty");
							else
								Logger.minor(this, "Priority "+priority+" (transient) is in cooldown for another "+(cooldownTime - now)+" "+TimeUtil.formatTime(cooldownTime - now));
						}
						result = null;
					}
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
				boolean persistent;
				SectoredRandomGrabArray chosenTracker = null;
				// If we can't find anything on perm (on the previous loop), try trans, and vice versa
				if(triedTrans) trans = null;
				if(triedPerm) perm = null;
				if(perm == null && trans == null) continue outer;
				else if(perm == null && trans != null) {
					chosenTracker = trans;
					triedTrans = true;
					long cooldownTime = context.cooldownTracker.getCachedWakeup(trans, false, container, now);
					if(cooldownTime > 0) {
						if(cooldownTime < wakeupTime) wakeupTime = cooldownTime;
						Logger.normal(this, "Priority "+choosenPriorityClass+" (transient) is in cooldown for another "+(cooldownTime - now)+" "+TimeUtil.formatTime(cooldownTime - now));
						continue outer;
					}
					persistent = false;
				} else if(perm != null && trans == null) {
					chosenTracker = perm;
					triedPerm = true;
					long cooldownTime = context.cooldownTracker.getCachedWakeup(perm, true, container, now);
					if(cooldownTime > 0) {
						if(cooldownTime < wakeupTime) wakeupTime = cooldownTime;
						Logger.normal(this, "Priority "+choosenPriorityClass+" (persistent) is in cooldown for another "+(cooldownTime - now)+" "+TimeUtil.formatTime(cooldownTime - now));
						continue outer;
					}
					container.activate(perm, 1);
					persistent = true;
				} else {
					container.activate(perm, 1);
					int permSize = perm.size();
					int transSize = trans.size();
					boolean choosePerm = random.nextInt(permSize + transSize) < permSize;
					if(choosePerm) {
						chosenTracker = perm;
						triedPerm = true;
						persistent = true;
					} else {
						chosenTracker = trans;
						triedTrans = true;
						persistent = false;
					}
					long cooldownTime = context.cooldownTracker.getCachedWakeup(trans, choosePerm, container, now);
					if(cooldownTime > 0) {
						if(cooldownTime < wakeupTime) wakeupTime = cooldownTime;
						Logger.normal(this, "Priority "+choosenPriorityClass+" (perm="+choosePerm+") is in cooldown for another "+(cooldownTime - now)+" "+TimeUtil.formatTime(cooldownTime - now));
						continue outer;
					}
				}
				
				if(logMINOR)
					Logger.minor(this, "Got priority tracker "+chosenTracker);
				RemoveRandomReturn val = chosenTracker.removeRandom(starter, persistent ? container : null, context, now);
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
				if(persistent)
					container.activate(req, 1); // FIXME
				if(chosenTracker.persistent() != persistent) {
					Logger.error(this, "Tracker.persistent()="+chosenTracker.persistent()+" but is in the queue for persistent="+persistent+" for "+chosenTracker);
					// FIXME fix it
				}
				if(req.persistent() != persistent) {
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
						schedCore.innerRegister(req, random, container, context, null);
					else
						schedTransient.innerRegister(req, random, container, context, null);
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
					List<BaseSendableGet> recent = schedTransient.recentSuccesses;
					BaseSendableGet altReq = null;
					synchronized(recent) {
						if(!recent.isEmpty()) {
							if(random.nextBoolean()) {
								altReq = recent.remove(recent.size()-1);
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
							altRGA = recentSuccesses.remove(recentSuccesses.size()-1);
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
	public boolean hasKey(Key key, BaseSendableGet getterWaiting, boolean persistent, ObjectContainer container) {
		if(keysFetching == null) {
			throw new NullPointerException();
		}
		long pid = -1;
		if(getterWaiting != null && persistent) {
			pid = container.ext().getID(getterWaiting);
		}
		synchronized(keysFetching) {
			boolean ret = keysFetching.contains(key);
			if(!ret) return ret;
			// It is being fetched. Add the BaseSendableGet to the wait list so it gets woken up when the request finishes.
			if(getterWaiting != null) {
				if(persistent) {
					Long[] waiting = persistentRequestsWaitingForKeysFetching.get(key);
					if(waiting == null) {
						persistentRequestsWaitingForKeysFetching.put(key, new Long[] { pid });
					} else {
						for(long l : waiting) {
							if(l == pid) return true;
						}
						Long[] newWaiting = new Long[waiting.length+1];
						System.arraycopy(waiting, 0, newWaiting, 0, waiting.length);
						newWaiting[waiting.length] = pid;
						persistentRequestsWaitingForKeysFetching.put(key, newWaiting);
					}
				} else {
					WeakReference<BaseSendableGet>[] waiting = transientRequestsWaitingForKeysFetching.get(key);
					if(waiting == null) {
						transientRequestsWaitingForKeysFetching.put(key, new WeakReference[] { new WeakReference<BaseSendableGet>(getterWaiting) });
					} else {
						for(WeakReference<BaseSendableGet> ref : waiting) {
							if(ref.get() == getterWaiting) return true;
						}
						WeakReference<BaseSendableGet>[] newWaiting = new WeakReference[waiting.length+1];
						System.arraycopy(waiting, 0, newWaiting, 0, waiting.length);
						newWaiting[waiting.length] = new WeakReference<BaseSendableGet>(getterWaiting);
						transientRequestsWaitingForKeysFetching.put(key, newWaiting);
					}
				}
			}
			return true;
		}
	}

	/** LOCKING: Caller should hold as few locks as possible */ 
	public void removeFetchingKey(final Key key) {
		Long[] persistentWaiting;
		WeakReference<BaseSendableGet>[] transientWaiting;
		if(logMINOR)
			Logger.minor(this, "Removing from keysFetching: "+key);
		if(key != null) {
			synchronized(keysFetching) {
				keysFetching.remove(key);
				persistentWaiting = this.persistentRequestsWaitingForKeysFetching.remove(key);
				transientWaiting = this.transientRequestsWaitingForKeysFetching.remove(key);
			}
			if(persistentWaiting != null || transientWaiting != null) {
				CooldownTracker tracker = sched.clientContext.cooldownTracker;
				if(persistentWaiting != null) {
					for(Long l : persistentWaiting)
						tracker.clearCachedWakeupPersistent(l);
				}
				if(transientWaiting != null) {
					for(WeakReference<BaseSendableGet> ref : transientWaiting) {
						BaseSendableGet get = ref.get();
						if(get == null) continue;
						synchronized(sched) {
							tracker.clearCachedWakeup(get, false, null);
						}
					}
				}
			}
		}
	}

	@Override
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

	@Override
	public long checkRecentlyFailed(Key key, boolean realTime) {
		Node node = sched.getNode();
		return node.clientCore.checkRecentlyFailed(key, realTime);
	}
	
}
