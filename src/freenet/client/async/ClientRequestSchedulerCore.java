/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;
import com.db4o.query.Query;

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
import freenet.support.PrioritizedSerialExecutor;
import freenet.support.RandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.SortedVectorByNumber;
import freenet.support.io.NativeThread;

/**
 * @author toad
 * A persistent class that functions as the core of the ClientRequestScheduler.
 * Does not refer to any non-persistable classes as member variables: Node must always 
 * be passed in if we need to use it!
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
class ClientRequestSchedulerCore extends ClientRequestSchedulerBase implements KeysFetchingLocally {
	
	/** Identifier in the database for the node we are attached to */
	private final long nodeDBHandle;
	final PersistentCooldownQueue persistentCooldownQueue;
	private transient long initTime;
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
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
	
	public final byte[] globalSalt;
	
	private transient List<RandomGrabArray> recentSuccesses;
	
	/**
	 * Fetch a ClientRequestSchedulerCore from the database, or create a new one.
	 * @param node
	 * @param forInserts
	 * @param forSSKs
	 * @param selectorContainer
	 * @param executor 
	 * @return
	 */
	public static ClientRequestSchedulerCore create(Node node, final boolean forInserts, final boolean forSSKs, ObjectContainer selectorContainer, long cooldownTime, PrioritizedSerialExecutor databaseExecutor, ClientRequestScheduler sched, ClientContext context) {
		final long nodeDBHandle = node.nodeDBHandle;
		if(selectorContainer == null) {
			ClientRequestSchedulerCore core;
			core = new ClientRequestSchedulerCore(node, forInserts, forSSKs, selectorContainer, cooldownTime);
			core.onStarted(selectorContainer, cooldownTime, sched, context);
			return core;
		}
		ObjectSet<ClientRequestSchedulerCore> results = selectorContainer.query(new Predicate<ClientRequestSchedulerCore>() {
			@Override
			public boolean match(ClientRequestSchedulerCore core) {
				if(core.nodeDBHandle != nodeDBHandle) return false;
				if(core.isInsertScheduler != forInserts) return false;
				if(core.isSSKScheduler != forSSKs) return false;
				return true;
			}
		});
		ClientRequestSchedulerCore core;
		if(results.hasNext()) {
			core = results.next();
			selectorContainer.activate(core, 2);
			System.err.println("Loaded core...");
			if(core.nodeDBHandle != nodeDBHandle) throw new IllegalStateException("Wrong nodeDBHandle");
			if(core.isInsertScheduler != forInserts) throw new IllegalStateException("Wrong isInsertScheduler");
			if(core.isSSKScheduler != forSSKs) throw new IllegalStateException("Wrong forSSKs");
		} else {
			core = new ClientRequestSchedulerCore(node, forInserts, forSSKs, selectorContainer, cooldownTime);
			selectorContainer.store(core);
			System.err.println("Created new core...");
		}
		core.onStarted(selectorContainer, cooldownTime, sched, context);
		return core;
	}

	ClientRequestSchedulerCore(Node node, boolean forInserts, boolean forSSKs, ObjectContainer selectorContainer, long cooldownTime) {
		super(forInserts, forSSKs);
		this.nodeDBHandle = node.nodeDBHandle;
		if(!forInserts) {
			this.persistentCooldownQueue = new PersistentCooldownQueue();
		} else {
			this.persistentCooldownQueue = null;
		}
		globalSalt = new byte[32];
		node.random.nextBytes(globalSalt);
	}

	private void onStarted(ObjectContainer container, long cooldownTime, ClientRequestScheduler sched, ClientContext context) {
		super.onStarted();
		System.err.println("insert scheduler: "+isInsertScheduler);
		if(!isInsertScheduler) {
			persistentCooldownQueue.setCooldownTime(cooldownTime);
		}
		this.sched = sched;
		this.initTime = System.currentTimeMillis();
		// We DO NOT want to rerun the query after consuming the initial set...
		if(!isInsertScheduler) {
			keysFetching = new HashSet<Key>();
			runningTransientInserts = null;
			this.recentSuccesses = new ArrayList<RandomGrabArray>();
		} else {
			keysFetching = null;
			runningTransientInserts = new HashSet<RunningTransientInsert>();
		}
		if(isInsertScheduler) {
		preRegisterMeRunner = new DBJob() {

			public boolean run(ObjectContainer container, ClientContext context) {
				synchronized(ClientRequestSchedulerCore.this) {
					if(registerMeSet != null) return false;
				}
				long tStart = System.currentTimeMillis();
				// FIXME REDFLAG EVIL DB4O BUG!!!
				// FIXME verify and file a bug
				// This code doesn't check the first bit!
				// I think this is related to the comparator...
//				registerMeSet = container.query(new Predicate() {
//					public boolean match(RegisterMe reg) {
//						if(reg.core != ClientRequestSchedulerCore.this) return false;
//						if(reg.key.addedTime > initTime) return false;
//						return true;
//					}
//				}, new Comparator() {
//
//					public int compare(Object arg0, Object arg1) {
//						RegisterMe reg0 = (RegisterMe) arg0;
//						RegisterMe reg1 = (RegisterMe) arg1;
//						RegisterMeSortKey key0 = reg0.key;
//						RegisterMeSortKey key1 = reg1.key;
//						return key0.compareTo(key1);
//					}
//					
//				});
				ObjectSet results = null;
				for(int i=RequestStarter.MAXIMUM_PRIORITY_CLASS;i<=RequestStarter.MINIMUM_PRIORITY_CLASS;i++) {
					Query query = container.query();
					query.constrain(RegisterMe.class);
					query.descend("core").constrain(ClientRequestSchedulerCore.this).and(query.descend("priority").constrain(i));
					results = query.execute();
					if(results.hasNext()) {
						break;
					} else results = null;
				}
				if(results == null)
					return false;
				// This throws NotSupported.
//				query.descend("core").constrain(this).identity().
//					and(query.descend("key").descend("addedTime").constrain(new Long(initTime)).smaller());
				/**
				 * FIXME DB4O
				 * db4o says it has indexed core. But then when we try to query, it produces a diagnostic
				 * suggesting we index it. And of course the query takes ages and uses tons of RAM. So don't
				 * try to filter by core at this point, deal with that later.
				 */
//				query.descend("core").constrain(ClientRequestSchedulerCore.this);
//				Evaluation eval = new Evaluation() {
//
//					public void evaluate(Candidate candidate) {
//						RegisterMe reg = (RegisterMe) candidate.getObject();
//						if(reg.key.addedTime > initTime || reg.core != ClientRequestSchedulerCore.this) {
//							candidate.include(false);
//							candidate.objectContainer().deactivate(reg.key, 1);
//							candidate.objectContainer().deactivate(reg, 1);
//						} else {
//							candidate.include(true);
//						}
//					}
//					
//				};
//				query.constrain(eval);
//				query.descend("key").descend("priority").orderAscending();
//				query.descend("key").descend("addedTime").orderAscending();
				synchronized(ClientRequestSchedulerCore.this) {
					registerMeSet = results;
				}
			long tEnd = System.currentTimeMillis();
			if(logMINOR)
				Logger.minor(this, "RegisterMe query took "+(tEnd-tStart)+" hasNext="+registerMeSet.hasNext()+" for insert="+isInsertScheduler+" ssk="+isSSKScheduler);
//				if(logMINOR)
//					Logger.minor(this, "RegisterMe query returned: "+registerMeSet.size());
				boolean boost = ClientRequestSchedulerCore.this.sched.isQueueAlmostEmpty();

				try {
					context.jobRunner.queue(registerMeRunner, (NativeThread.NORM_PRIORITY-1) + (boost ? 1 : 0), true);
				} catch (DatabaseDisabledException e) {
					// Do nothing, persistence is disabled
				}
				return false;
			}
		};
		registerMeRunner = new RegisterMeRunner();
		}
	}
	
	private transient DBJob preRegisterMeRunner;
	
	void start(DBJobRunner runner) {
		startRegisterMeRunner(runner);
	}
	
	private final void startRegisterMeRunner(DBJobRunner runner) {
		if(isInsertScheduler)
			try {
				runner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
			} catch (DatabaseDisabledException e) {
				// Persistence is disabled
			}
	}
	
	// We pass in the schedTransient to the next two methods so that we can select between either of them.
	
	private int removeFirstAccordingToPriorities(int fuzz, RandomSource random, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, short maxPrio, ObjectContainer container){
		SortedVectorByNumber result = null;
		
		short iteration = 0, priority;
		// we loop to ensure we try every possibilities ( n + 1)
		//
		// PRIO will do 0,1,2,3,4,5,6,0
		// TWEAKED will do rand%6,0,1,2,3,4,5,6
		while(iteration++ < RequestStarter.NUMBER_OF_PRIORITY_CLASSES + 1){
			priority = fuzz<0 ? tweakedPrioritySelector[random.nextInt(tweakedPrioritySelector.length)] : prioritySelector[Math.abs(fuzz % prioritySelector.length)];
			if(transientOnly)
				result = null;
			else
				result = priorities[priority];
			if(result == null)
				result = schedTransient.priorities[priority];
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
		SendableRequest req = removeFirstInner(fuzz, random, offeredKeys, starter, schedTransient, true, false, maxPrio, retryCount, context, container);
		if(isInsertScheduler && req instanceof SendableGet) {
			IllegalStateException e = new IllegalStateException("removeFirstInner returned a SendableGet on an insert scheduler!!");
			req.internalError(e, sched, container, context, req.persistent());
			throw e;
		}
		return maybeMakeChosenRequest(req, container, context);
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
				key = ((BaseSendableGet)req).getNodeKey(token, persistent() ? container : null);
				if(req instanceof SendableGet)
					ckey = ((SendableGet)req).getKey(token, persistent() ? container : null);
				else
					ckey = null;
			}
			ChosenBlock ret;
			assert(!req.persistent());
			if(key != null && key.getRoutingKey() == null)
				throw new NullPointerException();
			boolean localRequestOnly;
			boolean cacheLocalRequests;
			boolean ignoreStore;
			boolean canWriteClientCache;
			if(req instanceof SendableGet) {
				SendableGet sg = (SendableGet) req;
				FetchContext ctx = sg.getContext();
				localRequestOnly = ctx.localRequestOnly;
				cacheLocalRequests = ctx.cacheLocalRequests;
				ignoreStore = ctx.ignoreStore;
				canWriteClientCache = ctx.canWriteClientCache;
			} else {
				localRequestOnly = false;
				if(req instanceof SendableInsert) {
					cacheLocalRequests = ((SendableInsert)req).cacheInserts(null);
					canWriteClientCache = ((SendableInsert)req).canWriteClientCache(null);
				} else {
					cacheLocalRequests = false;
					canWriteClientCache = false;
				}
				ignoreStore = false;
			}
			ret = new TransientChosenBlock(req, token, key, ckey, localRequestOnly, cacheLocalRequests, ignoreStore, canWriteClientCache, sched);
			return ret;
		}
	}

	SendableRequest removeFirstInner(int fuzz, RandomSource random, OfferedKeysList offeredKeys, RequestStarter starter, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, boolean notTransient, short maxPrio, int retryCount, ClientContext context, ObjectContainer container) {
		// Priorities start at 0
		if(logMINOR) Logger.minor(this, "removeFirst()");
		boolean tryOfferedKeys = offeredKeys != null && (!notTransient) && random.nextBoolean();
		if(tryOfferedKeys) {
			if(offeredKeys.hasValidKeys(this, null, context))
				return offeredKeys;
		}
		int choosenPriorityClass = removeFirstAccordingToPriorities(fuzz, random, schedTransient, transientOnly, maxPrio, container);
		if(choosenPriorityClass == -1) {
			if(!tryOfferedKeys) {
				if(offeredKeys.hasValidKeys(this, null, context))
					return offeredKeys;
			}
			if(logMINOR)
				Logger.minor(this, "Nothing to do");
			return null;
		}
		if(maxPrio >= RequestStarter.MINIMUM_PRIORITY_CLASS)
			maxPrio = RequestStarter.MINIMUM_PRIORITY_CLASS;
		for(;choosenPriorityClass <= maxPrio;choosenPriorityClass++) {
			if(logMINOR) Logger.minor(this, "Using priority "+choosenPriorityClass);
			SortedVectorByNumber perm = null;
			if(!transientOnly)
				perm = priorities[choosenPriorityClass];
			SortedVectorByNumber trans = null;
			if(!notTransient)
				trans = schedTransient.priorities[choosenPriorityClass];
			if(perm == null && trans == null) {
				if(logMINOR) Logger.minor(this, "No requests to run: chosen priority empty");
				continue; // Try next priority
			}
			int permRetryIndex = 0;
			int transRetryIndex = 0;
			while(true) {
				int permRetryCount = perm == null ? Integer.MAX_VALUE : perm.getNumberByIndex(permRetryIndex);
				int transRetryCount = trans == null ? Integer.MAX_VALUE : trans.getNumberByIndex(transRetryIndex);
				if(choosenPriorityClass == maxPrio) {
					if(permRetryCount >= retryCount) {
						permRetryCount = Integer.MAX_VALUE;
					}
					if(transRetryCount >= retryCount) {
						transRetryCount = Integer.MAX_VALUE;
					}
				}
				if(permRetryCount == Integer.MAX_VALUE && transRetryCount == Integer.MAX_VALUE) {
					if(logMINOR) Logger.minor(this, "No requests to run: ran out of retrycounts on chosen priority");
					break; // Try next priority
				}
				SectoredRandomGrabArrayWithInt chosenTracker = null;
				SortedVectorByNumber trackerParent = null;
				if(permRetryCount == transRetryCount) {
					// Choose between them.
					SectoredRandomGrabArrayWithInt permRetryTracker = (SectoredRandomGrabArrayWithInt) perm.getByIndex(permRetryIndex);
					if(persistent() && permRetryTracker != null)
						container.activate(permRetryTracker, 1);
					SectoredRandomGrabArrayWithInt transRetryTracker = (SectoredRandomGrabArrayWithInt) trans.getByIndex(transRetryIndex);
					int permTrackerSize = permRetryTracker.size();
					int transTrackerSize = transRetryTracker.size();
					if(permTrackerSize + transTrackerSize == 0) {
						permRetryIndex++;
						transRetryIndex++;
						continue;
					}
					if(random.nextInt(permTrackerSize + transTrackerSize) > permTrackerSize) {
						chosenTracker = permRetryTracker;
						trackerParent = perm;
						permRetryIndex++;
					} else {
						chosenTracker = transRetryTracker;
						trackerParent = trans;
						transRetryIndex++;
					}
				} else if(permRetryCount < transRetryCount) {
					chosenTracker = (SectoredRandomGrabArrayWithInt) perm.getByIndex(permRetryIndex);
					if(persistent() && chosenTracker != null)
						container.activate(chosenTracker, 1);
					trackerParent = perm;
					permRetryIndex++;
				} else {
					chosenTracker = (SectoredRandomGrabArrayWithInt) trans.getByIndex(transRetryIndex);
					trackerParent = trans;
					transRetryIndex++;
				}
				if(logMINOR)
					Logger.minor(this, "Got retry count tracker "+chosenTracker);
				SendableRequest req = (SendableRequest) chosenTracker.removeRandom(starter, container, context);
				if(chosenTracker.isEmpty()) {
					trackerParent.remove(chosenTracker.getNumber(), container);
					if(chosenTracker.persistent())
						chosenTracker.removeFrom(container);
					if(trackerParent.isEmpty()) {
						if(logMINOR) Logger.minor(this, "Should remove priority");
					}
				}
				if(req == null) {
					if(logMINOR) Logger.minor(this, "No requests, adjusted retrycount "+chosenTracker.getNumber()+" ("+chosenTracker+") of priority "+choosenPriorityClass);
					continue; // Try next retry count.
				}
				if(chosenTracker.persistent())
					container.activate(req, 1); // FIXME
				if(req.persistent() != trackerParent.persistent()) {
					Logger.error(this, "Request.persistent()="+req.persistent()+" but is in the queue for persistent="+trackerParent.persistent()+" for "+req);
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
							baseRGA.remove(req, container);
						} else {
							// Okay, it's been removed already. Cool.
						}
					} else {
						Logger.error(this, "Could not find client grabber for client "+req.getClient(container)+" from "+chosenTracker);
					}
					if(req.persistent())
						innerRegister(req, random, container, null);
					else
						schedTransient.innerRegister(req, random, container, null);
					continue; // Try the next one on this retry count.
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
					if(!recent.isEmpty()) {
						if(random.nextBoolean()) {
							altReq = recent.remove(recent.size()-1);
						}
					}
					if(altReq != null && (altReq.isCancelled(container) || altReq.isEmpty(container))) {
						if(logMINOR)
							Logger.minor(this, "Ignoring cancelled recently succeeded item "+altReq);
						altReq = null;
					}
					if (altReq != null && altReq != req) {
						int prio = altReq.getPriorityClass(container);
						if(prio < choosenPriorityClass || (prio == choosenPriorityClass && fixRetryCount(altReq.getRetryCount()) <= chosenTracker.getNumber())) {
							// Use the recent one instead
							if(logMINOR)
								Logger.minor(this, "Recently succeeded (transient) req "+altReq+" (prio="+altReq.getPriorityClass(container)+" retry count "+altReq.getRetryCount()+") is better than "+req+" (prio="+req.getPriorityClass(container)+" retry "+req.getRetryCount()+"), using that");
							// Don't need to reregister, because removeRandom doesn't actually remove!
							req = altReq;
						} else {
							// Don't use the recent one
							if(logMINOR)
								Logger.minor(this, "Chosen req "+req+" is better, reregistering recently succeeded "+altReq);
							recent.add(altReq);
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
								if((prio < choosenPriorityClass || (prio == choosenPriorityClass && fixRetryCount(altReq.getRetryCount()) <= chosenTracker.getNumber()))
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
				if(logMINOR) Logger.minor(this, "removeFirst() returning "+req+" ("+chosenTracker.getNumber()+", prio "+
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

	@Override
	boolean persistent() {
		return true;
	}

	private transient ObjectSet registerMeSet;
	
	private transient RegisterMeRunner registerMeRunner;
	
	class RegisterMeRunner implements DBJob {

		public boolean run(ObjectContainer container, ClientContext context) {
			if(sched.databaseExecutor.getQueueSize(NativeThread.NORM_PRIORITY) > 100) {
				// If the queue isn't empty, reschedule at NORM-1, wait for the backlog to clear
				if(!sched.isQueueAlmostEmpty()) {
					try {
						context.jobRunner.queue(registerMeRunner, NativeThread.NORM_PRIORITY-1, false);
					} catch (DatabaseDisabledException e) {
						// Impossible
					}
					return false;
				}
			}
			long deadline = System.currentTimeMillis() + 10*1000;
			if(registerMeSet == null) {
				Logger.error(this, "registerMeSet is null for "+ClientRequestSchedulerCore.this+" ( "+this+" )");
				return false;
			}
			for(int i=0;i < 1000; i++) {
				try {
					if(!registerMeSet.hasNext()) break;
				} catch (NullPointerException t) {
					Logger.error(this, "DB4O thew NPE in hasNext(): "+t, t);
					// FIXME find some way to get a reproducible test case... I suspect it won't be easy :<
					try {
						context.jobRunner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
					} catch (DatabaseDisabledException e) {
						// Impossible
					}
					return true;
				} catch (ClassCastException t) {
					// WTF?!?!?!?!?!
					Logger.error(this, "DB4O thew ClassCastException in hasNext(): "+t, t);
					// FIXME find some way to get a reproducible test case... I suspect it won't be easy :<
					try {
						context.jobRunner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
					} catch (DatabaseDisabledException e) {
						// Impossible
					}
					return true;
				}
				long startNext = System.currentTimeMillis();
				RegisterMe reg = (RegisterMe) registerMeSet.next();
				container.activate(reg, 1);
				if(reg.bootID == context.bootID) {
					if(logMINOR) Logger.minor(this, "Not registering block "+reg+" as was added to the queue");
					continue;
				}
				// FIXME remove the leftover/old core handling at some point, an NPE is acceptable long-term.
				if(reg.core != ClientRequestSchedulerCore.this) {
					if(!container.ext().isStored(reg)) {
						if(logMINOR) Logger.minor(this, "Already deleted RegisterMe "+reg+" - skipping");
						continue;
					}
					if(reg.core == null) {
						Logger.error(this, "Leftover RegisterMe "+reg+" : core already deleted. THIS IS AN ERROR unless you have seen \"Old core not active\" messages before this point.");
						container.delete(reg);
						continue;
					}
					if(!container.ext().isActive(reg.core)) {
						Logger.error(this, "Old core not active in RegisterMe "+reg+" - duplicated cores????");
						container.delete(reg.core);
						container.delete(reg);
						continue;
					}
					if(logMINOR)
						Logger.minor(this, "Ignoring RegisterMe "+reg+" as doesn't belong to me: my insert="+isInsertScheduler+" my ssk="+isSSKScheduler+" his insert="+reg.core.isInsertScheduler+" his ssk="+reg.core.isSSKScheduler);
					container.deactivate(reg, 1);
					continue; // Don't delete.
				}
//				if(reg.key.addedTime > initTime) {
//					if(logMINOR) Logger.minor(this, "Ignoring RegisterMe as created since startup");
//					container.deactivate(reg.key, 1);
//					container.deactivate(reg, 1);
//					continue; // Don't delete
//				}
				if(logMINOR)
					Logger.minor(this, "Running RegisterMe "+reg+" for "+reg.nonGetRequest+" : "+reg.addedTime+" : "+reg.priority);
				// Don't need to activate, fields should exist? FIXME
				if(reg.nonGetRequest != null) {
					container.activate(reg.nonGetRequest, 1);
					if(reg.nonGetRequest.isCancelled(container)) {
						Logger.normal(this, "RegisterMe: request cancelled: "+reg.nonGetRequest);
					} else {
						if(logMINOR)
							Logger.minor(this, "Registering RegisterMe for insert: "+reg.nonGetRequest);
						sched.registerInsert(reg.nonGetRequest, true, false, container);
					}
					container.delete(reg);
					container.deactivate(reg.nonGetRequest, 1);
				}
				container.deactivate(reg, 1);
				if(System.currentTimeMillis() > deadline) break;
			}
			boolean boost = sched.isQueueAlmostEmpty();
			if(registerMeSet.hasNext())
				try {
					context.jobRunner.queue(registerMeRunner, (NativeThread.NORM_PRIORITY-1) + (boost ? 1 : 0), true);
				} catch (DatabaseDisabledException e) {
					// Impossible
				}
			else {
				if(logMINOR) Logger.minor(this, "RegisterMeRunner finished");
				synchronized(ClientRequestSchedulerCore.this) {
					registerMeSet = null;
				}
				// Always re-run the query. If there is nothing to register, it won't call back to us.
				preRegisterMeRunner.run(container, context);
			}
			return true;
		}
		
	}
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
		
	public void rerunRegisterMeRunner(DBJobRunner runner) {
		synchronized(this) {
			if(registerMeSet != null) return;
		}
		startRegisterMeRunner(runner);
	}
	
	@Override
	public synchronized long countQueuedRequests(ObjectContainer container, ClientContext context) {
		long ret = super.countQueuedRequests(container, context);
		long cooldown = persistentCooldownQueue.size(container);
		System.out.println("Cooldown queue size: "+cooldown);
		return ret + cooldown;
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

	@Override
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

