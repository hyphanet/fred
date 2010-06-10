package freenet.support;

import java.util.LinkedList;

import freenet.node.NodeStats;
import freenet.node.PrioRunnable;
import freenet.support.Logger.LoggerPriority;
import freenet.support.io.NativeThread;

public class PrioritizedSerialExecutor implements Executor {
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LoggerPriority.MINOR, this);
			}
		});
	}

	private final LinkedList<Runnable>[] jobs;
	private final int priority;
	private final int defaultPriority;
	private boolean waiting;
	private final boolean invertOrder;

	private String name;
	private Executor realExecutor;
	private boolean running;
	private final ExecutorIdleCallback callback;

	private static final int DEFAULT_JOB_TIMEOUT = 5*60*1000;
	private final int jobTimeout;

	private final Runner runner = new Runner();
	
	private final NodeStats statistics;

	class Runner implements PrioRunnable {

		Thread current;

		public int getPriority() {
			return priority;
		}

		public void run() {
			long lastDumped = System.currentTimeMillis();
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
							running=false;
							return;
						}
					}
				}
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
	public PrioritizedSerialExecutor(int priority, int internalPriorityCount, int defaultPriority, boolean invertOrder, int jobTimeout, ExecutorIdleCallback callback, NodeStats statistics) {
		@SuppressWarnings("unchecked") LinkedList<Runnable>[] jobs = (LinkedList<Runnable>[])new LinkedList[internalPriorityCount];
		for (int i=0;i<jobs.length;i++) {
			jobs[i] = new LinkedList<Runnable>();
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
			for(int i=0;i<jobs.length;i++) {
				if(!jobs[i].isEmpty()) {
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

	public void execute(Runnable job) {
		execute(job, "<noname>");
	}

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

	public void execute(Runnable job, String jobName, boolean fromTicker) {
		execute(job, jobName);
	}

	public int[] runningThreads() {
		int[] retval = new int[NativeThread.JAVA_PRIORITY_RANGE+1];
		if (running)
			retval[priority] = 1;
		return retval;
	}

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
	
	@SuppressWarnings("unchecked")
	public LinkedList<Runnable>[] getQueuedJobsByPriority() {
		final LinkedList<Runnable>[] jobsClone = (LinkedList<Runnable>[])new LinkedList[jobs.length];
		
		synchronized(jobs) {
			for(int i=0; i < jobs.length; ++i) {
				jobsClone[i] = (LinkedList<Runnable>) jobs[i].clone();
			}
		}
		
		return jobsClone;
	}

	public int getQueueSize(int priority) {
		synchronized(jobs) {
			return jobs[priority].size();
		}
	}

	public int getWaitingThreadsCount() {
		synchronized(jobs) {
			return (waiting ? 1 : 0);
		}
	}

}
