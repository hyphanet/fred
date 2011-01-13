package freenet.support;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import freenet.node.PrioRunnable;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

public class SerialExecutor implements Executor {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private final LinkedBlockingQueue<Runnable> jobs;
	private final Object syncLock;
	private final int priority;

	private volatile boolean threadWaiting;
	private volatile boolean threadStarted;

	private String name;
	private Executor realExecutor;

	private static final int NEWJOB_TIMEOUT = 5*60*1000;
	
	private Thread runningThread;

	private final Runnable runner = new PrioRunnable() {

		public int getPriority() {
			return priority;
		}

		public void run() {
			synchronized(syncLock) {
				runningThread = Thread.currentThread();
			}
			try {
			while(true) {
				synchronized (syncLock) {
						threadWaiting = true;
				}
				Runnable job = null;
						try {
					job = jobs.poll(NEWJOB_TIMEOUT, TimeUnit.MILLISECONDS);
						} catch (InterruptedException e) {
					// ignore
						}
				synchronized (syncLock) {
						threadWaiting=false;
						}
				if (job == null) {
					synchronized (syncLock) {
						threadStarted = false;
					}
					return;
				}

				try {
					job.run();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
					Logger.error(this, "While running "+job+" on "+this);
				}
			}
			} finally {
				synchronized(syncLock) {
					runningThread = null;
				}
			}
		}

	};

	public SerialExecutor(int priority) {
		jobs = new LinkedBlockingQueue<Runnable>();
		this.priority = priority;
		this.syncLock = new Object();
	}

	public void start(Executor realExecutor, String name) {
		assert(realExecutor != this);
		this.realExecutor=realExecutor;
		this.name=name;
		synchronized (syncLock) {
			if (!jobs.isEmpty())
				reallyStart();
		}
	}

	private void reallyStart() {
		synchronized (syncLock) {
		threadStarted=true;
		}
		if (logMINOR)
			Logger.minor(this, "Starting thread... " + name + " : " + runner);
		realExecutor.execute(runner, name);
	}

	public void execute(Runnable job) {
		execute(job, "<noname>");
	}

	public void execute(Runnable job, String jobName) {
		if (logMINOR)
			Logger.minor(this, "Running " + jobName + " : " + job + " started=" + threadStarted + " waiting="
			        + threadWaiting);
		jobs.add(job);

		synchronized (syncLock) {
			if (!threadStarted && realExecutor != null)
				reallyStart();
		}
	}

	public void execute(Runnable job, String jobName, boolean fromTicker) {
		execute(job, jobName);
	}

	public int[] runningThreads() {
		int[] retval = new int[NativeThread.JAVA_PRIORITY_RANGE+1];
		if (threadStarted && !threadWaiting)
			retval[priority] = 1;
		return retval;
	}

	public int[] waitingThreads() {
		int[] retval = new int[NativeThread.JAVA_PRIORITY_RANGE+1];
		synchronized (syncLock) {
			if(threadStarted && threadWaiting)
				retval[priority] = 1;
		}
		return retval;
	}

	public int getWaitingThreadsCount() {
		synchronized (syncLock) {
			return (threadStarted && threadWaiting) ? 1 : 0;
		}
	}

	public boolean onThread() {
		synchronized(syncLock) {
			return Thread.currentThread() == runningThread;
		}
	}
}
