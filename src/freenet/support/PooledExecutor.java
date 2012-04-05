/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import freenet.node.PrioRunnable;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * Pooled Executor implementation. Create a thread when we need one, let them die
 * after 5 minutes of inactivity.
 * @author toad
 */
public class PooledExecutor implements Executor {

	/** All threads running or waiting */
	@SuppressWarnings("unchecked")
	private final ArrayList<MyThread>[] runningThreads = new ArrayList[NativeThread.JAVA_PRIORITY_RANGE + 1];
	/** Threads waiting for a job */
	@SuppressWarnings("unchecked")
	private final ArrayList<MyThread>[] waitingThreads = new ArrayList[runningThreads.length];
	private volatile int waitingThreadsCount;
	AtomicLong[] threadCounter = new AtomicLong[runningThreads.length];
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
			runningThreads[i] = new ArrayList<MyThread>();
			waitingThreads[i] = new ArrayList<MyThread>();
			threadCounter[i] = new AtomicLong();
		}
		waitingThreadsCount = 0;
	}
	/** Maximum time a thread will wait for a job */
	static final int TIMEOUT = 1 * 60 * 1000;

	public void start() {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	}

	@Override
	public void execute(Runnable job) {
		execute(job, "<noname>");
	}

	@Override
	public void execute(Runnable job, String jobName) {
		execute(job, jobName, false);
	}

	@Override
	public void execute(Runnable runnable, String jobName, boolean fromTicker) {
		int prio = NativeThread.NORM_PRIORITY;
		if(runnable instanceof PrioRunnable)
			prio = ((PrioRunnable) runnable).getPriority();

		if(logMINOR)
			Logger.minor(this, "Executing " + runnable + " as " + jobName + " at prio " + prio);
		if(prio < NativeThread.MIN_PRIORITY || prio > NativeThread.MAX_PRIORITY)
			throw new IllegalArgumentException("Unreconized priority level : " + prio + '!');

		Job job = new Job(runnable, jobName);
		while(true) {
			MyThread t = null;
			boolean miss = false;
			synchronized(this) {
				jobCount++;
				if(!waitingThreads[prio - 1].isEmpty()) {
					t = waitingThreads[prio - 1].remove(waitingThreads[prio - 1].size() - 1);
					if (t != null)
						waitingThreadsCount--;
					if(logMINOR)
						Logger.minor(this, "Reusing thread " + t);
				} else {
					// Must create new thread
					if(ticker != null && (!fromTicker) && NativeThread.usingNativeCode() && prio > Thread.currentThread().getPriority()) {
						// Get the ticker to create a thread for it with the right priority, since we can't.
						// j16sdiz (22-Dec-2008): should we queue it? the ticker is "PacketSender", but it keep busying on non-packet related works
						ticker.queueTimedJob(runnable, jobName, 0, true, false);
						return;
					}
					miss = true;
				}
			}

			// miss
			if (miss) {
				long threadNo = threadCounter[prio - 1].getAndIncrement();
				// Will be coalesced by thread count listings if we use "@" or "for"
				t = new MyThread("Pooled thread awaiting work @" + threadNo+" for prio "+prio, job, threadNo, prio, !fromTicker);
				t.setDaemon(true);

				synchronized(this) {
					runningThreads[prio - 1].add(t);
					jobMisses++;

					if(logMINOR)
						Logger.minor(this, "Jobs: " + jobMisses + " misses of " + jobCount + " starting urgently " + jobName);
				}

				t.start();
				return;
			}

			// use existing thread
			synchronized(t) {
				if(!t.alive)
					continue;
				if(t.nextJob != null)
					continue;
				t.nextJob = job;

				// It is possible that we could get a wierd race condition with
				// notify()/wait() signalling on a thread being used by higher
				// level code. So we'd best use notifyAll().
				t.notifyAll();
			}

			if(logMINOR)
				synchronized(this) {
					Logger.minor(this, "Not starting: Jobs: " + jobMisses + " misses of " + jobCount + " starting urgently " + jobName);
				}
			return;
		}
	}

	@Override
	public synchronized int[] runningThreads() {
		int[] result = new int[runningThreads.length];
		for(int i = 0; i < result.length; i++)
			result[i] = runningThreads[i].size() - waitingThreads[i].size();
		return result;
	}

	@Override
	public synchronized int[] waitingThreads() {
		int[] result = new int[waitingThreads.length];
		for(int i = 0; i < result.length; i++)
			result[i] = waitingThreads[i].size();
		return result;
	}

	@Override
	public int getWaitingThreadsCount() {
		return waitingThreadsCount;
	}

	private static class Job {
		private final Runnable runnable;
		private final String name;

		Job(Runnable runnable, String name) {
			this.runnable = runnable;
			this.name = name;
		}
	}

	private class MyThread extends NativeThread {
		final String defaultName;
		volatile boolean alive = true;
		Job nextJob;
		final long threadNo;

		public MyThread(String defaultName, Job firstJob, long threadCounter, int prio, boolean dontCheckRenice) {
			super(defaultName, prio, dontCheckRenice);
			this.defaultName = defaultName;
			threadNo = threadCounter;
			nextJob = firstJob;
		}

		@Override
		public void realRun() {
			long ranJobs = 0;
			int nativePriority = getNativePriority();
			while(true) {
				Job job;

				synchronized(this) {
					job = nextJob;
					nextJob = null;
				}

				if(job == null) {
					synchronized(PooledExecutor.this) {
						waitingThreads[nativePriority - 1].add(this);
						waitingThreadsCount++;
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
					}
					synchronized(PooledExecutor.this) {
						if (waitingThreads[nativePriority - 1].remove(this))
							waitingThreadsCount--;

						synchronized(this) {
							job = nextJob;
							nextJob = null;
							// FIXME Fortify thinks this is double-checked locking. IMHO this is a false alarm.
							if(job == null)
								alive = false;
						}

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
					setName(job.name + "(" + threadNo + ")");
					job.runnable.run();
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
