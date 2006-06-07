package freenet.node;

import freenet.client.async.RequestScheduler;
import freenet.client.async.SendableRequest;
import freenet.support.Logger;

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
	/** Anything more important than FProxy */
	public static final short MAXIMUM_PRIORITY_CLASS = 0;
	/** FProxy etc */
	public static final short INTERACTIVE_PRIORITY_CLASS = 1;
	/** FProxy splitfile fetches */
	public static final short IMMEDIATE_SPLITFILE_PRIORITY_CLASS = 2;
	/** USK updates etc */
	public static final short UPDATE_PRIORITY_CLASS = 3;
	/** Bulk splitfile fetches */
	public static final short BULK_SPLITFILE_PRIORITY_CLASS = 4;
	/** Prefetch */
	public static final short PREFETCH_PRIORITY_CLASS = 5;
	/** Anything less important than prefetch (redundant??) */
	public static final short MINIMUM_PRIORITY_CLASS = 6;
	
	public static final short NUMBER_OF_PRIORITY_CLASSES = MINIMUM_PRIORITY_CLASS - MAXIMUM_PRIORITY_CLASS + 1; // include 0 and max !!
	
	public static boolean isValidPriorityClass(int prio) {
		return !(prio < MAXIMUM_PRIORITY_CLASS || prio > MINIMUM_PRIORITY_CLASS);
	}
	
	final BaseRequestThrottle throttle;
	RequestScheduler sched;
	final Node node;
	private long sentRequestTime;
	
	public RequestStarter(Node node, BaseRequestThrottle throttle, String name) {
		this.node = node;
		this.throttle = throttle;
		this.name = name;
	}

	void setScheduler(RequestScheduler sched) {
		this.sched = sched;
	}
	
	void start() {
		Thread t = new Thread(this, name);
		t.setDaemon(true);
		t.start();
	}
	
	final String name;
	
	public String toString() {
		return name;
	}
	
	void realRun() {
		SendableRequest req = null;
		while(true) {
			if(req == null) req = sched.removeFirst();
			if(req != null) {
				Logger.minor(this, "Running "+req);
				// Create a thread to handle starting the request, and the resulting feedback
				while(true) {
					try {
						Thread t = new Thread(new SenderThread(req), "RequestStarter$SenderThread for "+req);
						t.setDaemon(true);
						t.start();
						Logger.minor(this, "Started "+req+" on "+t);
						break;
					} catch (OutOfMemoryError e) {
						// Probably out of threads
						try {
							Thread.sleep(5000);
						} catch (InterruptedException e1) {
							// Ignore
						}
						System.err.println(e.getMessage());
					}
				}
				sentRequestTime = System.currentTimeMillis();
				// Wait
				long delay = throttle.getDelay();
				Logger.minor(this, "Delay="+delay+" from "+throttle);
				long sleepUntil = sentRequestTime + delay;
				long now;
				do {
					now = System.currentTimeMillis();
					if(now < sleepUntil)
						try {
							Thread.sleep(sleepUntil - now);
							Logger.minor(this, "Slept: "+(sleepUntil-now)+"ms");
						} catch (InterruptedException e) {
							// Ignore
						}
				} while(now < sleepUntil);
				return;
			} else {
				Logger.minor(this, "Waiting...");
				synchronized(this) {
					// Always take the lock on RequestStarter first.
					req = sched.removeFirst();
					if(req != null) {
						continue;
					}
					try {
						wait(1000);
					} catch (InterruptedException e) {
						// Ignore
					}
					return;
				}
			}
		}
	}
	
	public void run() {
		while(true) {
			try {
				realRun();
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
	}
	
	private class SenderThread implements Runnable {

		private final SendableRequest req;
		
		public SenderThread(SendableRequest req) {
			this.req = req;
		}

		public void run() {
			req.send(node);
		}
		
	}

}
