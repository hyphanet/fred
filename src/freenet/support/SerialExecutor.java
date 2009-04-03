package freenet.support;

import java.util.LinkedList;

import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

public class SerialExecutor implements Executor {

	private final LinkedList<Runnable> jobs;
	private final int priority;
	private boolean waiting;
	
	private String name;
	private Executor realExecutor;
	private boolean running;
	
	private static final int NEWJOB_TIMEOUT = 5*60*1000;
	
	private final Runnable runner = new PrioRunnable() {

		public int getPriority() {
			return priority;
		}

		public void run() {
			while(true) {
				Runnable job;
				synchronized(jobs) {
					if(jobs.isEmpty()) {
						waiting = true;
						try {
							//NB: notify only on adding work or this quits early.
							jobs.wait(NEWJOB_TIMEOUT);
						} catch (InterruptedException e) {
							// Ignore
						}
						waiting=false;
						if (jobs.isEmpty()) {
							running=false;
							return;
						}
					}
					job = jobs.removeFirst();
				}
				try {
					job.run();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
					Logger.error(this, "While running "+job+" on "+this);
				}
			}
		}
		
	};
	
	public SerialExecutor(int priority) {
		jobs = new LinkedList<Runnable>();
		this.priority = priority;
	}
	
	public void start(Executor realExecutor, String name) {
		this.realExecutor=realExecutor;
		this.name=name;
		synchronized (jobs) {
			if (!jobs.isEmpty())
				reallyStart(Logger.shouldLog(Logger.MINOR, this));
		}
	}
	
	private void reallyStart(boolean logMINOR) {
		running=true;
		if(logMINOR) Logger.minor(this, "Starting thread... "+name+" : "+runner);
		realExecutor.execute(runner, name);
	}
	
	public void execute(Runnable job, String jobName) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(jobs) {
			if(logMINOR) Logger.minor(this, "Running "+jobName+" : "+job+" running="+running+" waiting="+waiting);
			jobs.addLast(job);
			jobs.notifyAll();
			if (!running && realExecutor!=null) {
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

	public int getWaitingThreadsCount() {
		synchronized(jobs) {
			return (waiting ? 1 : 0);
		}
	}
}
