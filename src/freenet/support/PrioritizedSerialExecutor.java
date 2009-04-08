package freenet.support;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

public class PrioritizedSerialExecutor implements Executor {
	
	private final LinkedList<Runnable>[] jobs;
	private final int[] jobCount;
	
	private final int priority;
	private final int defaultPriority;
	private boolean waiting;
	private final boolean invertOrder;
	private final Map<String, Long> timeByJobClasses = new HashMap<String, Long>();
	
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
				boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
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
					if(logMINOR)
						Logger.minor(this, "Running job "+job);
					long start = System.currentTimeMillis();
					job.run();
					long end = System.currentTimeMillis();
					if(logMINOR) {
						Logger.minor(this, "Job "+job+" took "+(end-start)+"ms");
					synchronized(timeByJobClasses) {
						String name = job.toString();
						if(name.indexOf('@') > 0)
							name = name.substring(0, name.indexOf('@'));
						Long l = timeByJobClasses.get(name);
						if(l != null) {
							l = new Long(l.longValue() + (end-start));
						} else {
							l = new Long(end-start);
						}
						timeByJobClasses.put(name, l);
						if(logMINOR) {
							Logger.minor(this, "Total for class "+name+" : "+l);
							if(System.currentTimeMillis() > (lastDumped + 60*1000)) {
								Iterator i = timeByJobClasses.entrySet().iterator();
								while(i.hasNext()) {
									Map.Entry e = (Map.Entry) i.next();
									Logger.minor(this, "Class "+e.getKey()+" : total time "+e.getValue());
								}
								lastDumped = System.currentTimeMillis();
							}
						}
					}
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
						if(Logger.shouldLog(Logger.MINOR, this))
							Logger.minor(this, "Chosen job at priority "+i);
						synchronized (jobs) {
							Runnable r = jobs[i].removeFirst();
							jobCount[i]--;
							return r;
						}
					}
				}
			} else {
				for(int i=jobs.length-1;i>=0;i--) {
					if(!jobs[i].isEmpty()) {
						if(Logger.shouldLog(Logger.MINOR, this))
							Logger.minor(this, "Chosen job at priority "+i);
						synchronized (jobs) {
							Runnable r = jobs[i].removeFirst();
							jobCount[i]--;
							return r;
						}
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
			jobs[i] = new LinkedList<Runnable>();
		jobCount = new int[internalPriorityCount];
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
			jobCount[prio]++;
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
			jobCount[prio]++;
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

	public int[] queuedJobs() {
		int[] retval;
		synchronized(jobs) {
			retval = jobCount.clone();
		}
		return retval;
	}

	public int getQueueSize(int priority) {
		synchronized(jobs) {
			return jobCount[priority];
		}
	}

	public int getWaitingThreadsCount() {
		synchronized(jobs) {
			return (waiting ? 1 : 0);
		}
	}

}
