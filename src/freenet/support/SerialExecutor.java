package freenet.support;

import java.util.LinkedList;

import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

public class SerialExecutor implements Executor {

	private final LinkedList jobs;
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
					job = (Runnable) jobs.removeFirst();
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
		jobs = new LinkedList();
		this.priority = priority;
	}
	
	public void start(Executor realExecutor, String name) {
		this.realExecutor=realExecutor;
		this.name=name;
	}
	
	private void reallyStart() {
		running=true;
		realExecutor.execute(runner, name);
	}
	
	public void execute(Runnable job, String jobName) {
		synchronized(jobs) {
			jobs.addLast(job);
			jobs.notifyAll();
			if (!running)
				reallyStart();
		}
	}

	public void execute(Runnable job, String jobName, boolean fromTicker) {
		synchronized(jobs) {
			jobs.addLast(job);
			jobs.notifyAll();
			if (!running)
				reallyStart();
		}
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

}
