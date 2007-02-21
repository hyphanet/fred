/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.TokenBucket;
import freenet.support.math.RunningAverage;

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
		return !((prio < MAXIMUM_PRIORITY_CLASS) || (prio > MINIMUM_PRIORITY_CLASS));
	}
	
	final BaseRequestThrottle throttle;
	final TokenBucket inputBucket;
	final TokenBucket outputBucket;
	final RunningAverage averageInputBytesPerRequest;
	final RunningAverage averageOutputBytesPerRequest;
	RequestScheduler sched;
	final NodeClientCore core;
	private long sentRequestTime;
	private final boolean isInsert;
	
	public RequestStarter(NodeClientCore node, BaseRequestThrottle throttle, String name, TokenBucket outputBucket, TokenBucket inputBucket,
			RunningAverage averageOutputBytesPerRequest, RunningAverage averageInputBytesPerRequest, boolean isInsert) {
		this.core = node;
		this.throttle = throttle;
		this.name = name;
		this.outputBucket = outputBucket;
		this.inputBucket = inputBucket;
		this.averageOutputBytesPerRequest = averageOutputBytesPerRequest;
		this.averageInputBytesPerRequest = averageInputBytesPerRequest;
		this.isInsert = isInsert;
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
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(req == null) req = sched.removeFirst();
			if(req != null) {
				if(logMINOR) Logger.minor(this, "Running "+req);
				// Create a thread to handle starting the request, and the resulting feedback
				while(true) {
					try {
						Thread t = new Thread(new SenderThread(req), "RequestStarter$SenderThread for "+req);
						t.setDaemon(true);
						t.start();
						if(logMINOR) Logger.minor(this, "Started "+req+" on "+t);
						break;
					} catch (OutOfMemoryError e) {
						OOMHandler.handleOOM(e);
						System.err.println("Will retry above failed operation...");
						// Possibly out of threads
						try {
							Thread.sleep(5000);
						} catch (InterruptedException e1) {
							// Ignore
						}
					}
				}
				sentRequestTime = System.currentTimeMillis();
				// Wait
				long delay = throttle.getDelay();
				if(logMINOR) Logger.minor(this, "Delay="+delay+" from "+throttle);
				long sleepUntil = sentRequestTime + delay;
				inputBucket.blockingGrab((int)(Math.max(0, averageInputBytesPerRequest.currentValue())));
				outputBucket.blockingGrab((int)(Math.max(0, averageOutputBytesPerRequest.currentValue())));
				long now;
				do {
					now = System.currentTimeMillis();
					if(now < sleepUntil)
						try {
							Thread.sleep(sleepUntil - now);
							if(logMINOR) Logger.minor(this, "Slept: "+(sleepUntil-now)+"ms");
						} catch (InterruptedException e) {
							// Ignore
						}
				} while(now < sleepUntil);
				core.node.waitUntilNotOverloaded(isInsert);
				return;
			} else {
				if(logMINOR) Logger.minor(this, "Waiting...");				
					req = sched.removeFirst();
					if(req != null) {
						continue;
					}
					// Always take the lock on RequestStarter first.
					synchronized(this) {
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
			req.send(core); // FIXME check return value?
			if(Logger.shouldLog(Logger.MINOR, this)) 
				Logger.minor(this, "Finished "+req);
		}
		
	}

}
