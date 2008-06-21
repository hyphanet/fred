package freenet.support;

import java.util.LinkedList;

import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

public class PrioritizedSerialExecutor implements Executor {
	
	private final LinkedList[] jobs;
	private final int priority;
	private final int defaultPriority;
	private boolean waiting;
	private final boolean invertOrder;
	
	private String name;
	private Executor realExecutor;
	private boolean running;
	
	private static final int NEWJOB_TIMEOUT = 5*60*1000;

	private final Runner runner = new Runner();
	
	class Runner implements PrioRunnable {

		Thread current;
		
		public int getPriority() {
			return priority;
		}

		public void run() {
			current = Thread.currentThread();
			while(true) {
				Runnable job = null;
				synchronized(jobs) {
					job = checkQueue();
					if(job == null) {
						waiting = true;
						try {
							//NB: notify only on adding work or this quits early.
							jobs.wait(NEWJOB_TIMEOUT);
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
					if(Logger.shouldLog(Logger.MINOR, this))
						Logger.minor(this, "Running job "+job);
					job.run();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
					Logger.error(this, "While running "+job+" on "+this);
				}
			}
		}

		private Runnable checkQueue() {
			if(!invertOrder) {
				for(int i=0;i<jobs.length;i++) {
					if(!jobs[i].isEmpty()) {
						return (Runnable) jobs[i].removeFirst();
					}
				}
			} else {
				for(int i=jobs.length-1;i>=0;i--) {
					if(!jobs[i].isEmpty()) {
						return (Runnable) jobs[i].removeFirst();
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
	public PrioritizedSerialExecutor(int priority, int internalPriorityCount, int defaultPriority, boolean invertOrder) {
		jobs = new LinkedList[internalPriorityCount];
		for(int i=0;i<jobs.length;i++)
			jobs[i] = new LinkedList();
		this.priority = priority;
		this.defaultPriority = defaultPriority;
		this.invertOrder = invertOrder;
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
				reallyStart(Logger.shouldLog(Logger.MINOR, this));
		}
	}
	
	private void reallyStart(boolean logMINOR) {
		running=true;
		if(logMINOR) Logger.minor(this, "Starting thread... "+name+" : "+runner);
		realExecutor.execute(runner, name);
	}
	
	public void execute(Runnable job, String jobName) {
		int prio = defaultPriority;
		if(job instanceof PrioRunnable)
			prio = ((PrioRunnable) job).getPriority();
		execute(job, prio, jobName);
	}

	public void execute(Runnable job, int prio, String jobName) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(jobs) {
			if(logMINOR) 
				Logger.minor(this, "Running "+jobName+" : "+job+" priority "+prio+" running="+running+" waiting="+waiting);
			jobs[prio].addLast(job);
			jobs.notifyAll();
			if(!running && realExecutor != null) {
				reallyStart(logMINOR);
			}
		}
	}

	public void executeNoDupes(Runnable job, int prio, String jobName) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(jobs) {
			if(logMINOR) 
				Logger.minor(this, "Running "+jobName+" : "+job+" priority "+prio+" running="+running+" waiting="+waiting);
			if(jobs[prio].contains(job)) {
				if(logMINOR)
					Logger.minor(this, "Not adding duplicate job "+job);
				return;
			}
			jobs[prio].addLast(job);
			jobs.notifyAll();
			if(!running && realExecutor != null) {
				reallyStart(logMINOR);
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

}
