/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;
import com.db4o.types.Db4oList;
import com.db4o.types.Db4oMap;

import freenet.crypt.RandomSource;
import freenet.node.BaseSendableGet;
import freenet.node.Node;
import freenet.node.RequestStarter;
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
class ClientRequestSchedulerCore extends ClientRequestSchedulerBase {
	
	private static boolean logMINOR;
	/** Identifier in the database for the node we are attached to */
	private final long nodeDBHandle;
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	private final Map allRequestsByClientRequest;
	// FIXME cooldown queue ????
	// Can we make the cooldown queue non-persistent? It refers to SendableGet's ... so
	// keeping it in memory may be a problem...
	private final List /* <BaseSendableGet> */ recentSuccesses;

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
		super(forInserts ? null : selectorContainer.ext().collections().newHashMap(1024));
		this.nodeDBHandle = node.nodeDBHandle;
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		allRequestsByClientRequest = selectorContainer.ext().collections().newHashMap(32);
		recentSuccesses = selectorContainer.ext().collections().newLinkedList();
	}

	private void onStarted() {
		((Db4oMap)pendingKeys).activationDepth(1);
		((Db4oMap)allRequestsByClientRequest).activationDepth(1);
		((Db4oList)recentSuccesses).activationDepth(1);
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
