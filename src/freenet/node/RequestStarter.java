package freenet.node;

import java.util.LinkedList;
import java.util.Vector;

import freenet.support.Logger;
import freenet.support.UpdatableSortedLinkedList;
import freenet.support.UpdatableSortedLinkedListKilledException;

/**
 * Starts requests.
 * Nobody starts a request directly, you have to go through RequestStarter.
 * And you have to provide a RequestStarterClient. We do round robin between 
 * clients on the same priority level.
 */
public class RequestStarter implements Runnable {

	/*
	 * Priority classes
	 */
	/** Anything more important than fproxy */
	public static final short MAXIMUM_PRIORITY_CLASS = 0;
	/** Fproxy etc */
	public static final short INTERACTIVE_PRIORITY_CLASS = 1;
	/** Fproxy splitfile fetches */
	public static final short IMMEDIATE_SPLITFILE_PRIORITY_CLASS = 2;
	/** USK updates etc */
	public static final short UPDATE_PRIORITY_CLASS = 3;
	/** Bulk splitfile fetches */
	public static final short BULK_SPLITFILE_PRIORITY_CLASS = 4;
	/** Prefetch */
	public static final short PREFETCH_PRIORITY_CLASS = 5;
	/** Anything less important than prefetch (redundant??) */
	public static final short MINIMUM_PRIORITY_CLASS = 6;
	
	public static final short NUMBER_OF_PRIORITY_CLASSES = MINIMUM_PRIORITY_CLASS - MAXIMUM_PRIORITY_CLASS;
	
	// Clients registered
	final Vector clientsByPriority;
	final RequestThrottle throttle;
	/*
	 * Clients which are ready.
	 * How do we do round-robin?
	 * Have a list of clients which are ready to go, in priority order, and
	 * haven't gone this cycle.
	 * Have a list of clients which are ready to go next cycle, in priority
	 * order.
	 * Have each client track the cycle number in which it was last sent.
	 */
	final UpdatableSortedLinkedList clientsReadyThisCycle;
	final UpdatableSortedLinkedList clientsReadyNextCycle;
	/** Increment every time we go through the whole list */
	long cycleNumber;
	
	public RequestStarter(RequestThrottle throttle, String name) {
		clientsByPriority = new Vector();
		clientsReadyThisCycle = new UpdatableSortedLinkedList();
		clientsReadyNextCycle = new UpdatableSortedLinkedList();
		cycleNumber = 0;
		this.throttle = throttle;
		this.name = name;
		Thread t = new Thread(this, name);
		t.setDaemon(true);
		t.start();
	}

	final String name;
	
	public String toString() {
		return name;
	}
	
	public synchronized void registerClient(RequestStarterClient client) {
		int p = client.priority;
		LinkedList prio = makePriority(p);
		prio.add(client);
	}

	public synchronized void notifyReady(RequestStarterClient client) {
		Logger.minor(this, "notifyReady("+client+")");
		try {
			if(client.getCycleLastSent() == cycleNumber) {
				clientsReadyNextCycle.addOrUpdate(client);
			} else {
				// Can send immediately
				clientsReadyThisCycle.addOrUpdate(client);
			}
		} catch (UpdatableSortedLinkedListKilledException e) {
			throw new Error(e);
		}
		notifyAll();
	}
	
	private synchronized LinkedList makePriority(int p) {
		while(p >= clientsByPriority.size()) {
			clientsByPriority.add(new LinkedList());
		}
		return (LinkedList) clientsByPriority.get(p);
	}

	public void run() {
		long sentRequestTime = System.currentTimeMillis();
		while(true) {
			RequestStarterClient client;
			client = getNextClient();
			Logger.minor(this, "getNextClient() = "+client);
			if(client != null) {
				boolean success;
				try {
					success = client.send(cycleNumber);
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t);
					continue;
				}
				if(success) {
					sentRequestTime = System.currentTimeMillis();
					Logger.minor(this, "Sent");
					if(client.isReady()) {
						synchronized(this) {
							try {
								clientsReadyNextCycle.addOrUpdate(client);
							} catch (UpdatableSortedLinkedListKilledException e) {
								// Impossible
								throw new Error(e);
							}
						}
					}
				}
			}
			while(true) {
				long delay = throttle.getDelay();
				long sleepUntil = sentRequestTime + delay;
				long now = System.currentTimeMillis();
				if(sleepUntil < now) {
					if(waitingClients()) break;
					// Otherwise wait for notification
					try {
						synchronized(this) {
							wait(1000);
						}
					} catch (InterruptedException e) {
						// Ignore
					}
				} else {
					Logger.minor(this, "delay="+delay+"("+throttle+") sleep for "+(sleepUntil-now)+" for "+this);
					if(sleepUntil - now > 0)
						try {
							synchronized(this) {
								// At most sleep 500ms, then recompute.
								wait(Math.min(sleepUntil - now, 500));
							}
						} catch (InterruptedException e) {
							// Ignore
						}
				}
			}
		}
	}

	private synchronized boolean waitingClients() {
		return !(clientsReadyThisCycle.isEmpty() && clientsReadyNextCycle.isEmpty());
	}

	/**
	 * Get the next ready client.
	 */
	private synchronized RequestStarterClient getNextClient() {
		try {
			while(true) {
			if(clientsReadyThisCycle.isEmpty() && clientsReadyNextCycle.isEmpty())
				return null;
			if(clientsReadyThisCycle.isEmpty()) {
				cycleNumber++;
				clientsReadyNextCycle.moveTo(clientsReadyThisCycle);
			}
			RequestStarterClient c = (RequestStarterClient) clientsReadyThisCycle.removeLowest();
			if(c.getCycleLastSent() == cycleNumber) {
				clientsReadyNextCycle.add(c);
				continue;
			} else {
				c.setCycleLastSet(cycleNumber);
				return c;
			}
			}
		} catch (UpdatableSortedLinkedListKilledException e) {
			throw new Error(e);
		}
	}
}
