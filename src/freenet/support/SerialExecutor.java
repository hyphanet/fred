package freenet.support;

import java.util.LinkedList;

import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

public class SerialExecutor implements Executor {

	private final LinkedList jobs;
	private final int priority;
	private boolean waiting;
	
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
							jobs.wait();
						} catch (InterruptedException e) {
							// Ignore
						}
						continue;
					} else {
						job = (Runnable) jobs.removeFirst();
						waiting = false;
					}
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
		realExecutor.execute(runner, name);
	}
	
	public void execute(Runnable job, String jobName) {
		synchronized(jobs) {
			jobs.addLast(job);
		}
	}

	public void execute(Runnable job, String jobName, boolean fromTicker) {
		synchronized(jobs) {
			jobs.addLast(job);
		}
	}

	public int[] runningThreads() {
		int[] running = new int[NativeThread.JAVA_PRIORITY_RANGE+1];
		running[priority] = 1;
		return running;
	}

	public int[] waitingThreads() {
		int[] running = new int[NativeThread.JAVA_PRIORITY_RANGE+1];
		synchronized(jobs) {
			if(waiting)
				running[priority] = 1;
		}
		return running;
	}

}
