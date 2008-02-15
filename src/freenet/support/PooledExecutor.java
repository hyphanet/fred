/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import freenet.node.Ticker;
import freenet.support.io.NativeThread;
import java.util.ArrayList;

/**
 * Pooled Executor implementation. Create a thread when we need one, let them die
 * after 5 minutes of inactivity.
 * @author toad
 */
public class PooledExecutor implements Executor {

	private final ArrayList[] runningThreads /* <MyThread> */ = new ArrayList[NativeThread.JAVA_PRIO_RANGE];
	private final ArrayList[] waitingThreads /* <MyThread> */ = new ArrayList[runningThreads.length];
	long[] threadCounter = new long[runningThreads.length];
	private long jobCount;
	private long jobMisses;
	private static boolean logMINOR;
	// Ticker thread that runs at maximum priority.
	private Ticker ticker;
	
	public void setTicker(Ticker ticker) {
		this.ticker = ticker;
	}
	
	public PooledExecutor() {
		for(int i=0; i<runningThreads.length; i++) {
			runningThreads[i] = new ArrayList();
			waitingThreads[i] = new ArrayList();
			threadCounter[i] = 0;
		}
	}
	
	/** Maximum time a thread will wait for a job */
	static final int TIMEOUT = 5*60*1000;
	
	public void start() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public void execute(Runnable job, String jobName) {
		execute(job, jobName, Thread.NORM_PRIORITY);
	}
	
	public void execute(Runnable job, String jobName, int prio) {
		if(logMINOR) Logger.minor(this, "Executing "+job+" as "+jobName+" at prio "+prio);
		while(true) {
			MyThread t;
			boolean mustStart = false;
			boolean miss = false;
			synchronized(this) {
				jobCount++;
				if(!waitingThreads[prio].isEmpty()) {
					t = (MyThread) waitingThreads[prio].remove(waitingThreads[prio].size()-1);
				} else {
					// Must create new thread
					if(NativeThread.usingNativeCode() && prio < Thread.currentThread().getPriority()) {
						// Run on ticker
						ticker.queueTimedJob(job, 0);
					}
					// Will be coalesced by thread count listings if we use "@" or "for"
					t = new MyThread("Pooled thread awaiting work @"+(threadCounter[prio]++), threadCounter[prio], prio);
					t.setDaemon(true);
					mustStart = true;
					miss = true;
				}
			}
			synchronized(t) {
				if(!t.alive) continue;
				if(t.nextJob != null) continue;
				t.nextJob = job;
				if(!mustStart)
					// It is possible that we could get a wierd race condition with
					// notify()/wait() signalling on a thread being used by higher
					// level code. So we'd best use notifyAll().
					t.notifyAll();
			}
			t.setName(jobName+"("+t.threadNo+")");
			if(mustStart) {
				t.start();
				synchronized(this) {
					runningThreads[prio].add(t);
					if(miss)
						jobMisses++;
					if(logMINOR)
						Logger.minor(this, "Jobs: "+jobMisses+" misses of "+jobCount+" starting urgently "+jobName);
				}
			}
			return;
		}
	}

	public synchronized int[] waitingThreads() {
		int[] result = new int[waitingThreads.length];
		for(int i=0; i<result.length; i++)
			result[i] = waitingThreads[i].size();
		return result;
	}
	
	class MyThread extends NativeThread {
		final String defaultName;
		boolean alive = true;
		Runnable nextJob;
		final long threadNo;
		
		public MyThread(String defaultName, long threadCounter, int prio) {
			super(defaultName, prio);
			this.defaultName = defaultName;
			threadNo = threadCounter;
		}

		public void run() {
			super.run();
			long ranJobs = 0;
			int nativePriority = getNativePriority();
			while(true) {
				Runnable job;
				
				synchronized(this) {
					job = nextJob;
					nextJob = null;
				}
				
				if(job == null) {
					synchronized(PooledExecutor.this) {
						waitingThreads[nativePriority].add(this);
					}
					synchronized(this) {
						if(nextJob == null) {
							this.setName(defaultName);
							try {
								wait(TIMEOUT);
							} catch (InterruptedException e) {
								// Ignore
							}
						}
						job = nextJob;
						nextJob = null;
						if(job == null) {
							alive = false;
							// execute() won't give us another job if alive = false
						}
					}
					synchronized(PooledExecutor.this) {
						waitingThreads[nativePriority].remove(this);
						if(!alive) {
							runningThreads[nativePriority].remove(this);
							if(logMINOR)
								Logger.minor(this, "Exiting having executed "+ranJobs+" jobs : "+this);
							return;
						}
					}
				}
				
				// Run the job
				try {
					job.run();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" running job "+job, t);
				}
				ranJobs++;
			}
		}
		
	}
	
}
