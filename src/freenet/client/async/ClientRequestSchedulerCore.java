/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashSet;
import java.util.List;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;
import com.db4o.query.Query;
import com.db4o.types.Db4oList;
import com.db4o.types.Db4oMap;

import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableRequest;
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
class ClientRequestSchedulerCore extends ClientRequestSchedulerBase implements KeysFetchingLocally {
	
	private ObjectContainer container;
	private static boolean logMINOR;
	/** Identifier in the database for the node we are attached to */
	private final long nodeDBHandle;
	final PersistentCooldownQueue persistentCooldownQueue;
	private transient ClientRequestScheduler sched;
	
	/**
	 * All Key's we are currently fetching. 
	 * Locally originated requests only, avoids some complications with HTL, 
	 * and also has the benefit that we can see stuff that's been scheduled on a SenderThread
	 * but that thread hasn't started yet. FIXME: Both issues can be avoided: first we'd get 
	 * rid of the SenderThread and start the requests directly and asynchronously, secondly
	 * we'd move this to node but only track keys we are fetching at max HTL.
	 * LOCKING: Always lock this LAST.
	 */
	private transient HashSet keysFetching;
	
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
			core = new ClientRequestSchedulerCore(node, forInserts, forSSKs, selectorContainer, cooldownTime);
		}
		logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequestSchedulerCore.class);
		core.onStarted(selectorContainer, cooldownTime, sched, context);
		return core;
	}

	ClientRequestSchedulerCore(Node node, boolean forInserts, boolean forSSKs, ObjectContainer selectorContainer, long cooldownTime) {
		super(forInserts, forSSKs, forInserts ? null : selectorContainer.ext().collections().newHashMap(1024), selectorContainer.ext().collections().newHashMap(32), selectorContainer.ext().collections().newLinkedList());
		this.nodeDBHandle = node.nodeDBHandle;
		this.container = selectorContainer;
		if(!forInserts) {
			this.persistentCooldownQueue = new PersistentCooldownQueue();
		} else {
			this.persistentCooldownQueue = null;
		}
	}

	private void onStarted(ObjectContainer container, long cooldownTime, ClientRequestScheduler sched, ClientContext context) {
		if(!isInsertScheduler)
			((Db4oMap)pendingKeys).activationDepth(Integer.MAX_VALUE);
		((Db4oMap)allRequestsByClientRequest).activationDepth(Integer.MAX_VALUE);
		((Db4oList)recentSuccesses).activationDepth(Integer.MAX_VALUE);
		this.container = container;
		if(!isInsertScheduler) {
			persistentCooldownQueue.setCooldownTime(cooldownTime);
		}
		if(!isInsertScheduler)
			keysFetching = new HashSet();
		else
			keysFetching = null;
		this.sched = sched;
		InsertCompressor.load(container, context);
	}
	
	void start(DBJobRunner runner) {
		runner.queue(registerMeRunner, NativeThread.NORM_PRIORITY, true);
	}
	
	void fillStarterQueue() {
		ObjectSet results = container.query(new Predicate() {
			public boolean match(PersistentChosenRequest req) {
				if(req.core != ClientRequestSchedulerCore.this) return false;
				return true;
			}
		});
		while(results.hasNext()) {
			PersistentChosenRequest req = (PersistentChosenRequest) results.next();
			sched.addToStarterQueue(req);
			synchronized(keysFetching) {
				keysFetching.add(req.key);
			}
		}
	}
	
	// We pass in the schedTransient to the next two methods so that we can select between either of them.
	
	private int removeFirstAccordingToPriorities(boolean tryOfferedKeys, int fuzz, RandomSource random, OfferedKeysList[] offeredKeys, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, short maxPrio){
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
	
	// LOCKING: ClientRequestScheduler locks on (this) before calling. 
	// We prevent a number of race conditions (e.g. adding a retry count and then another 
	// thread removes it cos its empty) ... and in addToGrabArray etc we already sync on this.
	// The worry is ... is there any nested locking outside of the hierarchy?
	ChosenRequest removeFirst(int fuzz, RandomSource random, OfferedKeysList[] offeredKeys, RequestStarter starter, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, short maxPrio, int retryCount, ClientContext context) {
		SendableRequest req = removeFirstInner(fuzz, random, offeredKeys, starter, schedTransient, transientOnly, maxPrio, retryCount, context);
		if(req == null) return null;
		Object token = req.chooseKey(this, req.persistent() ? container : null, context);
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
			ChosenRequest ret;
			if(req.persistent()) {
				ret = new PersistentChosenRequest(this, req, token, key, ckey);
				container.set(ret);
			} else {
				ret = new ChosenRequest(req, token, key, ckey);
			}
			if(key != null)
				keysFetching.add(key);
			return ret;
		}
	}
	
	SendableRequest removeFirstInner(int fuzz, RandomSource random, OfferedKeysList[] offeredKeys, RequestStarter starter, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, short maxPrio, int retryCount, ClientContext context) {
		// Priorities start at 0
		if(logMINOR) Logger.minor(this, "removeFirst()");
		boolean tryOfferedKeys = offeredKeys != null && random.nextBoolean();
		int choosenPriorityClass = removeFirstAccordingToPriorities(tryOfferedKeys, fuzz, random, offeredKeys, schedTransient, transientOnly, maxPrio);
		if(choosenPriorityClass == -1 && offeredKeys != null && !tryOfferedKeys) {
			tryOfferedKeys = true;
			choosenPriorityClass = removeFirstAccordingToPriorities(tryOfferedKeys, fuzz, random, offeredKeys, schedTransient, transientOnly, maxPrio);
		}
		if(choosenPriorityClass == -1) {
			if(logMINOR)
				Logger.minor(this, "Nothing to do");
			return null;
		}
		for(;choosenPriorityClass <= RequestStarter.MINIMUM_PRIORITY_CLASS;choosenPriorityClass++) {
			if(logMINOR) Logger.minor(this, "Using priority "+choosenPriorityClass);
		if(tryOfferedKeys) {
			if(offeredKeys[choosenPriorityClass].hasValidKeys(this, null, context))
				return offeredKeys[choosenPriorityClass];
		}
		SortedVectorByNumber perm = null;
		if(!transientOnly)
			perm = priorities[choosenPriorityClass];
		SortedVectorByNumber trans = schedTransient.priorities[choosenPriorityClass];
		if(perm == null && trans == null) {
			if(logMINOR) Logger.minor(this, "No requests to run: chosen priority empty");
			return null;
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
				return null;
			}
			SectoredRandomGrabArrayWithInt chosenTracker = null;
			SortedVectorByNumber trackerParent = null;
			if(permRetryCount == transRetryCount) {
				// Choose between them.
				SectoredRandomGrabArrayWithInt permRetryTracker = (SectoredRandomGrabArrayWithInt) perm.getByIndex(permRetryIndex);
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
				trackerParent.remove(chosenTracker.getNumber());
				if(trackerParent.isEmpty()) {
					if(logMINOR) Logger.minor(this, "Should remove priority");
				}
			}
			if(req == null) {
				if(logMINOR) Logger.minor(this, "No requests, adjusted retrycount "+chosenTracker.getNumber()+" ("+chosenTracker+") of priority "+choosenPriorityClass);
				continue; // Try next retry count.
			} else if(req.getPriorityClass() != choosenPriorityClass) {
				// Reinsert it : shouldn't happen if we are calling reregisterAll,
				// maybe we should ask people to report that error if seen
				Logger.normal(this, "In wrong priority class: "+req+" (req.prio="+req.getPriorityClass()+" but chosen="+choosenPriorityClass+ ')');
				// Remove it.
				SectoredRandomGrabArrayWithObject clientGrabber = (SectoredRandomGrabArrayWithObject) chosenTracker.getGrabber(req.getClient());
				if(clientGrabber != null) {
					RandomGrabArray baseRGA = (RandomGrabArray) clientGrabber.getGrabber(req.getClientRequest());
					if(baseRGA != null) {
						baseRGA.remove(req);
					} else {
						Logger.error(this, "Could not find base RGA for requestor "+req.getClientRequest()+" from "+clientGrabber);
					}
				} else {
					Logger.error(this, "Could not find client grabber for client "+req.getClient()+" from "+chosenTracker);
				}
				innerRegister(req, random);
				continue; // Try the next one on this retry count.
			}
			// Check recentSuccesses
			List recent = req.persistent() ? recentSuccesses : schedTransient.recentSuccesses;
			SendableRequest altReq = null;
			if(!recent.isEmpty()) {
				if(random.nextBoolean()) {
					altReq = (BaseSendableGet) recent.remove(recent.size()-1);
				}
			}
			if(altReq != null && altReq.getPriorityClass() <= choosenPriorityClass && 
					fixRetryCount(altReq.getRetryCount()) <= chosenTracker.getNumber()) {
				// Use the recent one instead
				if(logMINOR)
					Logger.minor(this, "Recently succeeded req "+altReq+" is better, using that, reregistering chosen "+req);
				if(req.persistent())
					innerRegister(req, random);
				else
					schedTransient.innerRegister(req, random);
				req = altReq;
			} else {
				// Don't use the recent one
				if(logMINOR)
					Logger.minor(this, "Chosen req "+req+" is better, reregistering recently succeeded "+altReq);
				if(altReq != null)
					recent.add(altReq);
			}
			// Now we have chosen a request.
			if(logMINOR) Logger.debug(this, "removeFirst() returning "+req+" ("+chosenTracker.getNumber()+", prio "+
					req.getPriorityClass()+", retries "+req.getRetryCount()+", client "+req.getClient()+", client-req "+req.getClientRequest()+ ')');
			ClientRequester cr = req.getClientRequest();
			if(req.canRemove()) {
				if(req.persistent())
					removeFromAllRequestsByClientRequest(req, cr);
				else
					schedTransient.removeFromAllRequestsByClientRequest(req, cr);
				// Do not remove from the pendingKeys list.
				// Whether it is running a request, waiting to execute, or waiting on the
				// cooldown queue, ULPRs and backdoor coalescing should still be active.
			}
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

	boolean persistent() {
		return true;
	}

	ObjectContainer container() {
		return container;
	}

	private final RegisterMeRunner registerMeRunner = new RegisterMeRunner();
	
	class RegisterMeRunner implements DBJob {

		public void run(ObjectContainer container, ClientContext context) {
			Query query = container.query();
			query.constrain(RegisterMe.class);
			query.descend("core").constrain(ClientRequestSchedulerCore.this);
			query.descend("priority").orderAscending();
			query.descend("addedTime").orderAscending();
			ObjectSet result = query.execute();
			while(result.hasNext()) {
				RegisterMe reg = (RegisterMe) result.next();
				container.delete(reg);
				// Don't need to activate, fields should exist? FIXME
				try {
					sched.register(reg.getter, true);
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" running RegisterMeRunner", t);
					// Cancel the request, and commit so it isn't tried again.
					reg.getter.internalError(null, t, sched, container, context);
				}
				container.commit();
			}
		}
		
	}
	public void queueRegister(SendableRequest req, PrioritizedSerialExecutor databaseExecutor) {
		if(!databaseExecutor.onThread()) {
			throw new IllegalStateException("Not on database thread!");
		}
		RegisterMe reg = new RegisterMe(req, this);
		container.set(reg);
	}

	public void deleteRegisterMe(final SendableRequest req) {
		ObjectSet result = container.query(new Predicate() {
			public boolean match(RegisterMe reg) {
				if(reg.core != ClientRequestSchedulerCore.this) return false;
				if(reg.getter != req) return false;
				return true;
			}
		});
		while(result.hasNext()) {
			RegisterMe me = (RegisterMe) result.next();
			container.delete(me);
		}
	}
	
	public boolean hasKey(Key key) {
		synchronized(keysFetching) {
			return keysFetching.contains(key);
		}
	}

	public void removeFetchingKey(final Key key) {
		synchronized(keysFetching) {
			keysFetching.remove(key);
		}
		sched.databaseExecutor.execute(new Runnable() {
			public void run() {
				ObjectSet results = container.query(new Predicate() {
					public boolean match(PersistentChosenRequest req) {
						if(req.core != ClientRequestSchedulerCore.this) return false;
						return req.key.equals(key);
					}
				});
				if(results.hasNext()) {
					PersistentChosenRequest req = (PersistentChosenRequest) results.next();
					container.delete(req);
					container.commit();
				}
			}
		}, NativeThread.NORM_PRIORITY, "Remove fetching key");
	}

}

class RegisterMe {
	final SendableRequest getter;
	final ClientRequestSchedulerCore core;
	final short priority;
	final long addedTime;
	
	RegisterMe(SendableRequest getter, ClientRequestSchedulerCore core) {
		this.getter = getter;
		this.core = core;
		this.addedTime = System.currentTimeMillis();
		this.priority = getter.getPriorityClass();
	}
}

