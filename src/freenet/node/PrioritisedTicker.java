package freenet.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.OOMHandler;
import freenet.support.io.NativeThread;

public class PrioritisedTicker implements Ticker, Runnable {
	
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private final static class Job {
		final String name;
		final Runnable job;
		Job(String name, Runnable job) {
			this.name = name;
			this.job = job;
		}

		public boolean equals(Object o) {
			if(!(o instanceof Job)) return false;
			// Ignore the name, we are only interested in the job, needed for noDupes.
			return ((Job)o).job == job;
		}
		
		public int hashCode() {
			return job.hashCode();
		}
	}
	
	/** ~= Ticker :) */
	private final TreeMap<Long, Object> timedJobsByTime;
	private final HashSet<Object> timedJobsQueued;
	final Node node;
	final NativeThread myThread;
	static final int MAX_SLEEP_TIME = 200;
	
	PrioritisedTicker(Node node) {
		this.node = node;
		timedJobsByTime = new TreeMap<Long, Object>();
		timedJobsQueued = new HashSet<Object>();
		myThread = new NativeThread(this, "Ticker thread for " + node.getDarknetPortNumber(), NativeThread.MAX_PRIORITY, false);
		myThread.setDaemon(true);
	}
	
	void start() {
		Logger.normal(this, "Starting Ticker");
		System.out.println("Starting Ticker");
		long now = System.currentTimeMillis();
		long transition = Version.transitionTime();
		if(now < transition)
			queueTimedJob(new Runnable() {

					public void run() {
						freenet.support.Logger.OSThread.logPID(this);
						PeerNode[] nodes = node.peers.myPeers;
						for(int i = 0; i < nodes.length; i++) {
							PeerNode pn = nodes[i];
							pn.updateVersionRoutablity();
						}
					}
				}, transition - now);
		myThread.start();
	}

	public void run() {
		if(logMINOR) Logger.minor(this, "In Ticker.run()");
		freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			try {
				realRun();
			} catch(OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Will retry above failed operation...");
			} catch(Throwable t) {
				Logger.error(this, "Caught in PacketSender: " + t, t);
				System.err.println("Caught in PacketSender: " + t);
				t.printStackTrace();
			}
		}
	}
	
	private void realRun() {
		long now = System.currentTimeMillis();
		
		List<Job> jobsToRun = null;
		
		long sleepTime = MAX_SLEEP_TIME;

		synchronized(timedJobsByTime) {
			while(!timedJobsByTime.isEmpty()) {
				Long tRun = timedJobsByTime.firstKey();
				if(tRun.longValue() <= now) {
					if(jobsToRun == null)
						jobsToRun = new ArrayList<Job>();
					Object o = timedJobsByTime.remove(tRun);
					if(o instanceof Job[]) {
						Job[] r = (Job[]) o;
						for(int i = 0; i < r.length; i++) {
							jobsToRun.add(r[i]);
							timedJobsQueued.remove(r[i]);
						}
					} else {
						Job r = (Job) o;
						jobsToRun.add(r);
						timedJobsQueued.remove(r);
					}
				} else {
					sleepTime = Math.min(sleepTime, tRun.longValue() - now);
					break;
				}
			}
		}

		if(jobsToRun != null)
			for(Job r : jobsToRun) {
				if(logMINOR)
					Logger.minor(this, "Running " + r);
				if(r.job instanceof FastRunnable)
					// Run in-line

					try {
						r.job.run();
					} catch(Throwable t) {
						Logger.error(this, "Caught " + t + " running " + r, t);
					}
				else
					try {
						node.executor.execute(r.job, r.name, true);
					} catch(OutOfMemoryError e) {
						OOMHandler.handleOOM(e);
						System.err.println("Will retry above failed operation...");
						queueTimedJob(r.job, r.name, 200, true, false);
					} catch(Throwable t) {
						Logger.error(this, "Caught in PacketSender: " + t, t);
						System.err.println("Caught in PacketSender: " + t);
						t.printStackTrace();
					}
			}

		if(sleepTime > 0) {
			// Update logging only when have time to do so
			try {
				if(logMINOR)
					Logger.minor(this, "Sleeping for " + sleepTime);
				synchronized(this) {
					wait(sleepTime);
				}
			} catch(InterruptedException e) {
				// Ignore, just wake up. Probably we got interrupt()ed
				// because a new job came in.
			}
		}
	}


	public void queueTimedJob(Runnable job, long offset) {
		queueTimedJob(job, "Scheduled job: "+job, offset, false, false);
	}

	/**
	 * Queue a job at a specific time.
	 * @param runner The job to run. FastRunnable's get run directly on the PacketSender thread.
	 * @param name The name of the job, the thread running it will temporarily take this name,
	 * assuming it is run on a separate thread.
	 * @param offset The time at which to run the job in milliseconds after
	 * System.currentTimeMillis().
	 * @param runOnTickerAnyway If false, run jobs with offset <=0 on the ticker, to preserve
	 * their thread priorities; if true, jobs to run immediately through the executor (which
	 * normally will also preserve thread priorities, but may need to call back via
	 * runOnTickerAnyway=true if it needs to increase the thread priority).
	 * @param noDupes Don't run this job if it is already scheduled. Relatively expensive, O(n)
	 * with queued jobs. Necessary for Announcer to ensure that we don't get exponentially
	 * increasing numbers of announcement check jobs queued, while ensuring that we do always
	 * have one queued within the given period.
	 */
	public void queueTimedJob(Runnable runner, String name, long offset, boolean runOnTickerAnyway, boolean noDupes) {
		// Run directly *if* that won't cause any priority problems.
		if(offset <= 0 && !runOnTickerAnyway) {
			if(logMINOR) Logger.minor(this, "Running directly: "+runner);
			node.executor.execute(runner, name);
			return;
		}
		Job job = new Job(name, runner);
		if(offset < 0) offset = 0;
		long now = System.currentTimeMillis();
		Long l = Long.valueOf(offset + now);
		synchronized(timedJobsByTime) {
			if(noDupes) {
				if(timedJobsQueued.contains(new Job(name, runner))) {
					Logger.normal(this, "Not re-running as already queued: "+runner+" for "+name);
					return;
				}
			}
			Object o = timedJobsByTime.get(l);
			if(o == null)
				timedJobsByTime.put(l, job);
			else if(o instanceof Job)
				timedJobsByTime.put(l, new Job[]{(Job) o, job});
			else if(o instanceof Job[]) {
				Job[] r = (Job[]) o;
				Job[] jobs = new Job[r.length + 1];
				System.arraycopy(r, 0, jobs, 0, r.length);
				jobs[jobs.length - 1] = job;
				timedJobsByTime.put(l, jobs);
			}
			timedJobsQueued.add(job);
		}
		if(offset < MAX_SLEEP_TIME) {
			wakeUp();
		}
	}
	
	/** Wake up, and run any queued jobs. */
	void wakeUp() {
		// Wake up if needed
		synchronized(this) {
			notifyAll();
		}
	}

	public Executor getExecutor() {
		return node.executor;
	}

}
