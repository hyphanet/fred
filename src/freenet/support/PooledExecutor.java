/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import freenet.node.PrioRunnable;
import freenet.node.Ticker;
import freenet.support.io.NativeThread;
import java.util.ArrayList;

/**
 * Pooled Executor implementation. Create a thread when we need one, let them die
 * after 5 minutes of inactivity.
 * @author toad
 */
public class PooledExecutor implements Executor {

	/** All threads running or waiting */
	private final ArrayList[] runningThreads /* <MyThread> */ = new ArrayList[NativeThread.JAVA_PRIORITY_RANGE + 1];
	/** Threads waiting for a job */
	private final ArrayList[] waitingThreads /* <MyThread> */ = new ArrayList[runningThreads.length];
	long[] threadCounter = new long[runningThreads.length];
	private long jobCount;
	private long jobMisses;
	private static boolean logMINOR;
	// Ticker thread that runs at maximum priority.
	private Ticker ticker;

	public synchronized void setTicker(Ticker ticker) {
		this.ticker = ticker;
	}

	public PooledExecutor() {
		for(int i = 0; i < runningThreads.length; i++) {
			runningThreads[i] = new ArrayList();
			waitingThreads[i] = new ArrayList();
			threadCounter[i] = 0;
		}
	}
	/** Maximum time a thread will wait for a job */
	static final int TIMEOUT = 5 * 60 * 1000;

	public void start() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	public void execute(Runnable job, String jobName) {
		execute(job, jobName, false);
	}

	public void execute(Runnable job, String jobName, boolean fromTicker) {
		int prio = NativeThread.NORM_PRIORITY;
		if(job instanceof PrioRunnable)
			prio = ((PrioRunnable) job).getPriority();

		if(logMINOR)
			Logger.minor(this, "Executing " + job + " as " + jobName + " at prio " + prio);
		if(prio < NativeThread.MIN_PRIORITY || prio > NativeThread.MAX_PRIORITY)
			throw new IllegalArgumentException("Unreconized priority level : " + prio + '!');
		while(true) {
			MyThread t;
			boolean mustStart = false;
			boolean miss = false;
			synchronized(this) {
				jobCount++;
				if(!waitingThreads[prio - 1].isEmpty()) {
					t = (MyThread) waitingThreads[prio - 1].remove(waitingThreads[prio - 1].size() - 1);
					if(logMINOR)
						Logger.minor(this, "Reusing thread " + t);
				} else {
					// Must create new thread
					if((!fromTicker) && NativeThread.usingNativeCode() && prio > Thread.currentThread().getPriority()) {
						// Run on ticker
						ticker.queueTimedJob(job, jobName, 0, true);
						return;
					}
					// Will be coalesced by thread count listings if we use "@" or "for"
					t = new MyThread("Pooled thread awaiting work @" + (threadCounter[prio - 1]), threadCounter[prio - 1], prio, !fromTicker);
					threadCounter[prio - 1]++;
					t.setDaemon(true);
					mustStart = true;
					miss = true;
				}
			}
			synchronized(t) {
				if(!t.alive)
					continue;
				if(t.nextJob != null)
					continue;
				t.nextJob = job;
				if(!mustStart)
					// It is possible that we could get a wierd race condition with
					// notify()/wait() signalling on a thread being used by higher
					// level code. So we'd best use notifyAll().
					t.notifyAll();
			}
			t.setName(jobName + "(" + t.threadNo + ")");
			if(mustStart) {
				t.start();
				synchronized(this) {
					runningThreads[prio - 1].add(t);
					if(miss)
						jobMisses++;
					if(logMINOR)
						Logger.minor(this, "Jobs: " + jobMisses + " misses of " + jobCount + " starting urgently " + jobName);
				}
			} else
				if(logMINOR)
					synchronized(this) {
						Logger.minor(this, "Not starting: Jobs: " + jobMisses + " misses of " + jobCount + " starting urgently " + jobName);
					}
			return;
		}
	}

	public synchronized int[] runningThreads() {
		int[] result = new int[runningThreads.length];
		for(int i = 0; i < result.length; i++)
			result[i] = runningThreads[i].size() - waitingThreads[i].size();
		return result;
	}

	public synchronized int[] waitingThreads() {
		int[] result = new int[waitingThreads.length];
		for(int i = 0; i < result.length; i++)
			result[i] = waitingThreads[i].size();
		return result;
	}

	class MyThread extends NativeThread {

		final String defaultName;
		volatile boolean alive = true;
		Runnable nextJob;
		final long threadNo;

		public MyThread(String defaultName, long threadCounter, int prio, boolean dontCheckRenice) {
			super(defaultName, prio, dontCheckRenice);
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
						waitingThreads[nativePriority - 1].add(this);
					}
					synchronized(this) {
						if(nextJob == null) {
							this.setName(defaultName);
							try {
								wait(TIMEOUT);
							} catch(InterruptedException e) {
							// Ignore
							}
						}
						job = nextJob;
						nextJob = null;
						if(job == null)
							alive = false;
					}
					synchronized(PooledExecutor.this) {
						waitingThreads[nativePriority - 1].remove(this);
						if(!alive) {
							runningThreads[nativePriority - 1].remove(this);
							if(logMINOR)
								Logger.minor(this, "Exiting having executed " + ranJobs + " jobs : " + this);
							return;
						}
					}
				}

				// Run the job
				try {
					job.run();
				} catch (OutOfMemoryError e) {
					OOMHandler.handleOOM(e);
				} catch(Throwable t) {
					Logger.error(this, "Caught " + t + " running job " + job, t);
				}
				ranJobs++;
			}
		}
	}
}
