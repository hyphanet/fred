package freenet.client.async;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import freenet.crypt.RandomSource;
import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SortedVectorByNumber;

/**
 * Every X seconds, the RequestSender calls the ClientRequestScheduler to
 * ask for a request to start. A request is then started, in its own 
 * thread. It is removed at that point.
 */
public class ClientRequestScheduler implements RequestScheduler {

	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	private final SortedVectorByNumber[] priorities;
	// we have one for inserts and one for requests
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final RandomSource random;
	private final HashMap allRequestsByClientRequest;
	private final RequestStarter starter;
	private final Node node;
	
	// FIXME : shoudln't be hardcoded !
	private int[] prioritySelecter = { 
			0, 0, 0, 0, 0, 0, 0,
			1, 1, 1, 1, 1, 1,
			2, 2, 2, 2, 2,
			3, 3, 3, 3,
			4, 4, 4,
			5, 5,
			6 
	};
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, RandomSource random, RequestStarter starter, Node node) {
		this.starter = starter;
		this.random = random;
		this.node = node;
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		allRequestsByClientRequest = new HashMap();
	}
	
	public void register(SendableRequest req) {
		Logger.minor(this, "Registering "+req, new Exception("debug"));
		if((!isInsertScheduler) && req instanceof ClientPutter)
			throw new IllegalArgumentException("Expected a ClientPut: "+req);
		if(req instanceof SendableGet) {
			SendableGet getter = (SendableGet)req;
			if(!getter.ignoreStore()) {
				ClientKeyBlock block;
				try {
					block = node.fetchKey(getter.getKey());
				} catch (KeyVerifyException e) {
					// Verify exception, probably bogus at source;
					// verifies at low-level, but not at decode.
					getter.onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED));
					return;
				}
				if(block != null) {
					Logger.minor(this, "Can fulfill immediately from store");
					getter.onSuccess(block, true);
					return;
				}
			}
		}
		innerRegister(req);
		synchronized(starter) {
			starter.notifyAll();
		}
	}
	
	private synchronized void innerRegister(SendableRequest req) {
		SectoredRandomGrabArrayWithInt grabber = 
			makeGrabArray(req.getPriorityClass(), req.getRetryCount());
		grabber.add(req.getClient(), req);
		HashSet v = (HashSet) allRequestsByClientRequest.get(req.getClientRequest());
		if(v == null) {
			v = new HashSet();
			allRequestsByClientRequest.put(req.getClientRequest(), v);
		}
		v.add(req);
		Logger.minor(this, "Registered "+req+" on prioclass="+req.getPriorityClass()+", retrycount="+req.getRetryCount());
	}

	private synchronized SectoredRandomGrabArrayWithInt makeGrabArray(short priorityClass, int retryCount) {
		if(priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS || priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS)
			throw new IllegalStateException("Invalid priority: "+priorityClass+" - range is "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" (most important) to "+RequestStarter.MINIMUM_PRIORITY_CLASS+" (least important)");
		SortedVectorByNumber prio = priorities[priorityClass];
		if(prio == null) {
			prio = new SortedVectorByNumber();
			priorities[priorityClass] = prio;
		}
		SectoredRandomGrabArrayWithInt grabber = (SectoredRandomGrabArrayWithInt) prio.get(retryCount);
		if(grabber == null) {
			grabber = new SectoredRandomGrabArrayWithInt(random, retryCount);
			prio.add(grabber);
			Logger.minor(this, "Registering retry count "+retryCount+" with prioclass "+priorityClass);
		}
		return grabber;
	}

	public synchronized SendableRequest removeFirst() {
		if(node.currentScheduler == Node.SCHEDULER_IMPROVED_1)
			return improved_scheduler_1_removeFirst();
		else
			return default_Scheduler_removeFirst();
	}
	
	public SendableRequest improved_scheduler_1_removeFirst() {
		// Priorities start at 0
		Logger.minor(this, "removeFirst() (improved_1)");	
		short count = 6;
		while(count>0){
			int i = random.nextInt(prioritySelecter.length);
			SortedVectorByNumber s = priorities[prioritySelecter[i]];
			if(s == null) {
				Logger.minor(this, "Priority "+prioritySelecter[i]+" is null");
				count--;
				continue;
			}
			
			i=prioritySelecter[i];
			
			SectoredRandomGrabArrayWithInt rga = (SectoredRandomGrabArrayWithInt) s.getFirst(); // will discard finished items
			if(rga == null) {
				Logger.minor(this, "No retrycount's in priority "+i);
				priorities[i] = null;
				count--;
				continue;
			}
			SendableRequest req = (SendableRequest) rga.removeRandom();
			if(rga.isEmpty()) {
				Logger.minor(this, "Removing retrycount "+rga.getNumber());
				s.remove(rga.getNumber());
				if(s.isEmpty()) {
					Logger.minor(this, "Removing priority "+i);
					priorities[i] = null;
				}
			}
			if(req == null) {
				Logger.minor(this, "No requests in priority "+i+", retrycount "+rga.getNumber()+" ("+rga+")");
				count--;
				continue;
			}
			if(req.getPriorityClass() > i) {
				// Reinsert it
				Logger.minor(this, "In wrong priority class: "+req);
				innerRegister(req);
				continue;
			}
			Logger.minor(this, "removeFirst() returning "+req+" ("+rga.getNumber()+")");
			ClientRequester cr = req.getClientRequest();
			HashSet v = (HashSet) allRequestsByClientRequest.get(cr);
			v.remove(req);
			if(v.isEmpty())
				allRequestsByClientRequest.remove(cr);
			return req;
		}
		return null;
	}
	
	public SendableRequest default_Scheduler_removeFirst() {
		// Priorities start at 0
		Logger.minor(this, "removeFirst()");
		for(int i=0;i<RequestStarter.MINIMUM_PRIORITY_CLASS;i++) {
			SortedVectorByNumber s = priorities[i];
			if(s == null) {
				Logger.minor(this, "Priority "+i+" is null");
				continue;
			}
			while(true) {
				SectoredRandomGrabArrayWithInt rga = (SectoredRandomGrabArrayWithInt) s.getFirst(); // will discard finished items
				if(rga == null) {
					Logger.minor(this, "No retrycount's in priority "+i);
					priorities[i] = null;
					break;
				}
				SendableRequest req = (SendableRequest) rga.removeRandom();
				if(rga.isEmpty()) {
					Logger.minor(this, "Removing retrycount "+rga.getNumber());
					s.remove(rga.getNumber());
					if(s.isEmpty()) {
						Logger.minor(this, "Removing priority "+i);
						priorities[i] = null;
					}
				}
				if(req == null) {
					Logger.minor(this, "No requests in priority "+i+", retrycount "+rga.getNumber()+" ("+rga+")");
					continue;
				}
				if(req.getPriorityClass() > i) {
					// Reinsert it
					Logger.minor(this, "In wrong priority class: "+req);
					innerRegister(req);
					continue;
				}
				Logger.minor(this, "removeFirst() returning "+req+" ("+rga.getNumber()+")");
				ClientRequester cr = req.getClientRequest();
				HashSet v = (HashSet) allRequestsByClientRequest.get(cr);
				v.remove(req);
				if(v.isEmpty())
					allRequestsByClientRequest.remove(cr);
				return req;
			}
		}
		Logger.minor(this, "No requests to run");
		return null;
	}

	public void reregisterAll(ClientRequester request) {
		synchronized(this) {
			HashSet h = (HashSet) allRequestsByClientRequest.get(request);
			if(h != null) {
				Iterator i = h.iterator();
				while(i.hasNext()) {
					SendableRequest req = (SendableRequest) i.next();
					// Don't actually remove it as removing it is a rather slow operation
					// It will be removed when removeFirst() reaches it.
					//grabArray.remove(req);
					innerRegister(req);
				}
			}
		}
		synchronized(starter) {
			starter.notifyAll();
		}
	}
}
