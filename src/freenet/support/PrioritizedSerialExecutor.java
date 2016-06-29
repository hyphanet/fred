package freenet.support;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.util.ArrayDeque;

import freenet.node.NodeStats;
import freenet.node.PrioRunnable;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

public class PrioritizedSerialExecutor implements Executor {
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private final ArrayDeque<Runnable>[] jobs;
	private final int priority;
	private final int defaultPriority;
	private boolean waiting;
	private final boolean invertOrder;

	private String name;
	private Executor realExecutor;
	private boolean running;
	private final ExecutorIdleCallback callback;

	private static final long DEFAULT_JOB_TIMEOUT = MINUTES.toMillis(5);
	private final long jobTimeout;

	private final Runner runner = new Runner();
	
	private final NodeStats statistics;

	class Runner implements PrioRunnable {

		Thread current;

		@Override
		public int getPriority() {
			return priority;
		}

		@Override
		public void run() {
			synchronized(jobs) {
				if(current != null) {
					if(current.isAlive()) {
						Logger.error(this, "Already running a thread for "+this+" !!", new Exception("error"));
						return;
					}
				}
				current = Thread.currentThread();
			}
			try {
			boolean calledIdleCallback = false;
			while(true) {
				Runnable job = null;
				synchronized(jobs) {
					job = checkQueue();
					if(job == null) {
						waiting = true;
						try {
							//NB: notify only on adding work or this quits early.
							jobs.wait(jobTimeout);
						} catch (InterruptedException e) {
							// Ignore
						}
						waiting=false;
						job = checkQueue();
						if(job == null) {
							if(calledIdleCallback || callback == null) {
								running=false;
								current = null;
								return;
							}
						}
					}
				}
				if(job == null) {
					try {
						callback.onIdle();
					} catch (Throwable t) {
						Logger.error(this, "Idle callback failed: "+t, t);
					}
					calledIdleCallback = true;
					continue;
				}
				calledIdleCallback = false;
				try {
					if(logMINOR)
						Logger.minor(this, "Running job "+job);
					long start = System.currentTimeMillis();
					job.run();
					long end = System.currentTimeMillis();
					if(logMINOR) {
						Logger.minor(this, "Job "+job+" took "+(end-start)+"ms");
					}
				
					if(statistics != null) {
						statistics.reportDatabaseJob(job.toString(), end-start);
					}
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
					Logger.error(this, "While running "+job+" on "+this);
				}
			}
			} finally {
				synchronized(jobs) {
					current = null;
					running = false;
				}
			}
		}

		private Runnable checkQueue() {
			if(!invertOrder) {
				for(int i=0;i<jobs.length;i++) {
					if(!jobs[i].isEmpty()) {
						if(logMINOR)
							Logger.minor(this, "Chosen job at priority "+i);
						return jobs[i].removeFirst();
					}
				}
			} else {
				for(int i=jobs.length-1;i>=0;i--) {
					if(!jobs[i].isEmpty()) {
						if(logMINOR)
							Logger.minor(this, "Chosen job at priority "+i);
						return jobs[i].removeFirst();
					}
				}
			}
			return null;
		}

	};

	/**
	 *
	 * @param priority
	 * @param internalPriorityCount
	 * @param defaultPriority
	 * @param invertOrder Set if the priorities are thread priorities. Unset if they are request priorities. D'oh!
	 */
	public PrioritizedSerialExecutor(int priority, int internalPriorityCount, int defaultPriority, boolean invertOrder, long jobTimeout, ExecutorIdleCallback callback, NodeStats statistics) {
		@SuppressWarnings("unchecked")
		ArrayDeque<Runnable>[] jobs = (ArrayDeque<Runnable>[])
			new ArrayDeque<?>[internalPriorityCount];
		for (int i=0;i<jobs.length;i++) {
			jobs[i] = new ArrayDeque<Runnable>();
		}
		this.jobs = jobs;
		this.priority = priority;
		this.defaultPriority = defaultPriority;
		this.invertOrder = invertOrder;
		this.jobTimeout = jobTimeout;
		this.callback = callback;
		this.statistics = statistics;
	}

	public PrioritizedSerialExecutor(int priority, int internalPriorityCount, int defaultPriority, boolean invertOrder) {
		this(priority, internalPriorityCount, defaultPriority, invertOrder, DEFAULT_JOB_TIMEOUT, null, null);
	}

	public void start(Executor realExecutor, String name) {
		this.realExecutor=realExecutor;
		this.name=name;
		synchronized (jobs) {
			boolean empty = true;
			for(ArrayDeque<Runnable> l: jobs) {
				if(!l.isEmpty()) {
					empty = false;
					break;
				}
			}
			if(!empty)
				reallyStart();
		}
	}

	private void reallyStart() {
		synchronized(jobs) {
			if(running) {
				Logger.error(this, "Not reallyStart()ing: ALREADY RUNNING", new Exception("error"));
				return;
			}
			running=true;
			if(logMINOR) Logger.minor(this, "Starting thread... "+name+" : "+runner, new Exception("debug"));
			realExecutor.execute(runner, name);
		}
	}

	@Override
	public void execute(Runnable job) {
		execute(job, "<noname>");
	}

	@Override
	public void execute(Runnable job, String jobName) {
		int prio = defaultPriority;
		if(job instanceof PrioRunnable)
			prio = ((PrioRunnable) job).getPriority();
		execute(job, prio, jobName);
	}

	public void execute(Runnable job, int prio, String jobName) {
		synchronized(jobs) {
			if(logMINOR)
				Logger.minor(this, "Queueing "+jobName+" : "+job+" priority "+prio+", executor state: running="+running+" waiting="+waiting);
			jobs[prio].addLast(job);
			jobs.notifyAll();
			if(!running && realExecutor != null) {
				reallyStart();
			}
		}
	}

	public void executeNoDupes(Runnable job, int prio, String jobName) {
		synchronized(jobs) {
			if(jobs[prio].contains(job)) {
				if(logMINOR)
					Logger.minor(this, "Not queueing job: Job already queued: "+job);
				return;
			}

			if(logMINOR)
				Logger.minor(this, "Queueing "+jobName+" : "+job+" priority "+prio+", executor state: running="+running+" waiting="+waiting);

			jobs[prio].addLast(job);
			jobs.notifyAll();
			if(!running && realExecutor != null) {
				reallyStart();
			}
		}
	}

	@Override
	public void execute(Runnable job, String jobName, boolean fromTicker) {
		execute(job, jobName);
	}

	@Override
	public int[] runningThreads() {
		int[] retval = new int[NativeThread.JAVA_PRIORITY_RANGE+1];
		if (running)
			retval[priority] = 1;
		return retval;
	}

	@Override
	public int[] waitingThreads() {
		int[] retval = new int[NativeThread.JAVA_PRIORITY_RANGE+1];
		synchronized(jobs) {
			if(waiting)
				retval[priority] = 1;
		}
		return retval;
	}

	public boolean onThread() {
		Thread running = Thread.currentThread();
		synchronized(jobs) {
			if(runner == null) return false;
			return runner.current == running;
		}
	}

	public int[] getQueuedJobsCountByPriority() {
		int[] retval = new int[jobs.length];
		synchronized(jobs) {
			for(int i=0;i<retval.length;i++)
				retval[i] = jobs[i].size();
		}
		return retval;
	}
	
	public Runnable[][] getQueuedJobsByPriority() {
		final Runnable[][] ret = new Runnable[jobs.length][];
		
		synchronized(jobs) {
			for(int i=0; i < jobs.length; ++i) {
				ret[i] = jobs[i].toArray(new Runnable[jobs[i].size()]);
			}
		}
		
		return ret;
	}

	public int getQueueSize(int priority) {
		synchronized(jobs) {
			return jobs[priority].size();
		}
	}

	@Override
	public int getWaitingThreadsCount() {
		synchronized(jobs) {
			return (waiting ? 1 : 0);
		}
	}

	public boolean anyQueued() {
		synchronized(jobs) {
			for(int i=0;i<jobs.length;i++)
				if(jobs[i].size() > 0) return true;
		}
		return false;
	}

}
