/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;

import freenet.keys.Key;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.RandomGrabArrayItem;
import freenet.support.RandomGrabArrayItemExclusionList;
import freenet.support.TokenBucket;
import freenet.support.math.RunningAverage;

/**
 * Starts requests.
 * Nobody starts a request directly, you have to go through RequestStarter.
 * And you have to provide a RequestStarterClient. We do round robin between 
 * clients on the same priority level.
 */
public class RequestStarter implements Runnable, KeysFetchingLocally, RandomGrabArrayItemExclusionList {

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
	
	/** If true, local requests are subject to shouldRejectRequest(). If false, they are only subject to the token
	 * buckets and the thread limit. FIXME make configurable. */
	private static final boolean LOCAL_REQUESTS_COMPETE_FAIRLY = true;
	
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
	final NodeStats stats;
	private long sentRequestTime;
	private final boolean isInsert;
	private final boolean isSSK;
	
	public RequestStarter(NodeClientCore node, BaseRequestThrottle throttle, String name, TokenBucket outputBucket, TokenBucket inputBucket,
			RunningAverage averageOutputBytesPerRequest, RunningAverage averageInputBytesPerRequest, boolean isInsert, boolean isSSK) {
		this.core = node;
		this.stats = core.nodeStats;
		this.throttle = throttle;
		this.name = name;
		this.outputBucket = outputBucket;
		this.inputBucket = inputBucket;
		this.averageOutputBytesPerRequest = averageOutputBytesPerRequest;
		this.averageInputBytesPerRequest = averageInputBytesPerRequest;
		this.isInsert = isInsert;
		this.isSSK = isSSK;
		if(!isInsert) keysFetching = new HashSet();
	}

	void setScheduler(RequestScheduler sched) {
		this.sched = sched;
	}
	
	void start() {
		core.getExecutor().execute(this, name);
	}
	
	final String name;
	
	public String toString() {
		return name;
	}
	
	void realRun() {
		SendableRequest req = null;
		sentRequestTime = System.currentTimeMillis();
		// The last time at which we sent a request or decided not to
		long cycleTime = sentRequestTime;
		while(true) {
			// Allow 5 minutes before we start killing requests due to not connecting.
			OpennetManager om;
			if(core.node.peers.countConnectedPeers() < 3 && (om = core.node.getOpennet()) != null &&
					System.currentTimeMillis() - om.getCreationTime() < 5*60*1000) {
				try {
					synchronized(this) {
						wait(1000);
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				continue;
			}
			sched.moveKeysFromCooldownQueue();
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(req == null) req = sched.removeFirst();
			if(req != null) {
				if(logMINOR) Logger.minor(this, "Running "+req);
				// Wait
				long delay = throttle.getDelay();
				if(logMINOR) Logger.minor(this, "Delay="+delay+" from "+throttle);
				long sleepUntil = cycleTime + delay;
				if(!LOCAL_REQUESTS_COMPETE_FAIRLY) {
					inputBucket.blockingGrab((int)(Math.max(0, averageInputBytesPerRequest.currentValue())));
					outputBucket.blockingGrab((int)(Math.max(0, averageOutputBytesPerRequest.currentValue())));
				}
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
				String reason;
				if(LOCAL_REQUESTS_COMPETE_FAIRLY) {
					if((reason = stats.shouldRejectRequest(true, isInsert, isSSK, true, false, null)) != null) {
						if(logMINOR)
							Logger.minor(this, "Not sending local request: "+reason);
						// Wait one throttle-delay before trying again
						cycleTime = System.currentTimeMillis();
						continue; // Let local requests compete with all the others
					}
				} else {
					stats.waitUntilNotOverloaded(isInsert);
				}
			} else {
				if(logMINOR) Logger.minor(this, "Waiting...");				
				// Always take the lock on RequestStarter first. AFAICS we don't synchronize on RequestStarter anywhere else.
				// Nested locks here prevent extra latency when there is a race, and therefore allow us to sleep indefinitely
				synchronized(this) {
					req = sched.removeFirst();
					if(req == null) {
						try {
							wait(100*1000); // as close to indefinite as I'm comfortable with! Toad
						} catch (InterruptedException e) {
							// Ignore
						}
					}
				}
			}
			if(req == null) continue;
			if(!startRequest(req, logMINOR)) {
				if(!req.isCancelled())
					Logger.normal(this, "No requests to start on "+req);
			}
			req = null;
			cycleTime = sentRequestTime = System.currentTimeMillis();
		}
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
	private HashSet keysFetching;
	
	private boolean startRequest(SendableRequest req, boolean logMINOR) {
		// Create a thread to handle starting the request, and the resulting feedback
		Object keyNum = null;
		Key key = null;
		while(true) {
			try {
				keyNum = req.chooseKey(isInsert ? null : this);
				if(keyNum == null) return false;
				if(!isInsert) {
					key = ((BaseSendableGet)req).getNodeKey(keyNum);
					if(key == null) return false;
					synchronized(keysFetching) {
						keysFetching.add(key);
					}
				}
				core.getExecutor().execute(new SenderThread(req, keyNum, key), "RequestStarter$SenderThread for "+req);
				if(logMINOR) Logger.minor(this, "Started "+req+" key "+keyNum);
				return true;
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Will retry above failed operation...");
				// Possibly out of threads
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e1) {
					// Ignore
				}
				synchronized(keysFetching) {
					if(key != null) keysFetching.remove(key);
				}
			} catch (Throwable t) {
				if(keyNum != null) {
					// Re-queue
					Logger.error(this, "Caught "+t+" while trying to start request");
					req.internalError(keyNum, t, sched);
					return true; // Sort of ... maybe it will clear
				}
				synchronized(keysFetching) {
					if(key != null) keysFetching.remove(key);
				}
			}
		}
	}

	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			try {
				realRun();
            } catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
	}
	
	private class SenderThread implements Runnable {

		private final SendableRequest req;
		private final Object keyNum;
		private final Key key;
		
		public SenderThread(SendableRequest req, Object keyNum, Key key) {
			this.req = req;
			this.keyNum = keyNum;
			this.key = key;
		}

		public void run() {
			try {
		    freenet.support.Logger.OSThread.logPID(this);
			stats.reportOutgoingLocalRequestLocation(key.toNormalizedDouble());
			if(!req.send(core, sched, keyNum)) {
				if(!req.isCancelled())
					Logger.error(this, "run() not able to send a request on "+req);
				else
					Logger.normal(this, "run() not able to send a request on "+req+" - request was cancelled");
			}
			if(Logger.shouldLog(Logger.MINOR, this)) 
				Logger.minor(this, "Finished "+req);
			} finally {
				if(!isInsert) {
					synchronized(keysFetching) {
						keysFetching.remove(key);
					}
				}
			}
		}
		
	}

	public void wakeUp() {
		synchronized(this) {
			notifyAll();
		}
	}

	public boolean hasKey(Key key) {
		synchronized(keysFetching) {
			return keysFetching.contains(key);
		}
	}

	public boolean exclude(RandomGrabArrayItem item) {
		if(isInsert) return false;
		BaseSendableGet get = (BaseSendableGet) item;
		if(get.hasValidKeys(this))
			return false;
		Logger.normal(this, "Excluding (no valid keys): "+get);
		return true;
	}

}
