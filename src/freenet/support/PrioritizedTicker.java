package freenet.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

import freenet.node.FastRunnable;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

public class PrioritizedTicker implements Ticker, Runnable {
	
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

		@Override
		public boolean equals(Object o) {
			if(!(o instanceof Job)) return false;
			// Ignore the name, we are only interested in the job, needed for noDupes.
			return ((Job)o).job == job;
		}
		
		@Override
		public int hashCode() {
			return job.hashCode();
		}
	}
	
	/** ~= Ticker :) */
	private final TreeMap<Long, Object> timedJobsByTime;
	private final HashMap<Job, Long> timedJobsQueued;
	final NativeThread myThread;
	final Executor executor;
	static final int MAX_SLEEP_TIME = 200;
	
	public PrioritizedTicker(Executor executor, int portNumber) {
		this.executor = executor;
		timedJobsByTime = new TreeMap<Long, Object>();
		timedJobsQueued = new HashMap<Job, Long>();
		myThread = new NativeThread(this, "Ticker thread for " + portNumber, NativeThread.MAX_PRIORITY, false);
		myThread.setDaemon(true);
	}
	
	public void start() {
		Logger.normal(this, "Starting Ticker");
		System.out.println("Starting Ticker");
		myThread.start();
	}

	@Override
	public void run() {
		if(logMINOR) Logger.minor(this, "In Ticker.run()");
		freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			try {
				realRun();
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
						for(Job r: (Job[]) o) {
							jobsToRun.add(r);
							timedJobsQueued.remove(r);
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
						executor.execute(r.job, r.name, true);
					} catch(Throwable t) {
						Logger.error(this, "Caught in PacketSender: " + t, t);
						System.err.println("Caught in PacketSender: " + t);
						t.printStackTrace();
                                                System.err.println("Will retry above failed operation...");
                                                queueTimedJob(r.job, r.name, 200, true, false);
					}
			}

		if(sleepTime > 0) {
			try {
			    sleep(sleepTime);
			} catch(InterruptedException e) {
				// Ignore, just wake up. Probably we got interrupt()ed
				// because a new job came in.
			}
		}
	}

	protected void sleep(long sleepTime) throws InterruptedException {
        if(logMINOR)
            Logger.minor(this, "Sleeping for " + sleepTime);
        synchronized(this) {
            wait(sleepTime);
        }
    }

    @Override
	public void queueTimedJob(Runnable job, long offset) {
		queueTimedJob(job, "Scheduled job: "+job, offset, false, false);
	}
	
	/**
	 * Queue a job at a specific time (offset in milliseconds from "now").
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
	@Override
	public void queueTimedJob(Runnable runner, String name, long offset, boolean runOnTickerAnyway, boolean noDupes) {
		// Run directly *if* that won't cause any priority problems.
		long now = System.currentTimeMillis();
        if(offset < 0) offset = 0;
		queueTimedJobInner(runner, name, now+offset, offset, runOnTickerAnyway, noDupes);
	}

	/** Queue a job at a specific time (absolute time in milliseconds). If the time given has
	 * passed already, then run the job ASAP. 
	 * @param time The time at which to run the job. @see System.currentTimeMillis()
	 */
	@Override
	public void queueTimedJobAbsolute(Runnable runner, String name, long time, 
            boolean runOnTickerAnyway, boolean noDupes) {
	    long now = System.currentTimeMillis();
	    queueTimedJobInner(runner, name, time, time-now, runOnTickerAnyway, noDupes);
	}
	
	/** Queue a job at a specific absolute time. 
	 * @param runJobAt The absolute time at which the job should run.
	 * @param offset The offset in milliseconds from "now" (i.e. some recent call to 
	 * System.currentTimeMillis()). */
    private void queueTimedJobInner(Runnable runner, String name, long runJobAt, long offset, 
            boolean runOnTickerAnyway, boolean noDupes) {
        if(noDupes) runOnTickerAnyway = true;
        if(offset <= 0 && !runOnTickerAnyway) {
            if(logMINOR) Logger.minor(this, "Running directly: "+runner);
            executor.execute(runner, name);
            return;
        }
        Job job = new Job(name, runner);
        synchronized(timedJobsByTime) {
            if(noDupes) {
                Long alreadyQueuedAt = timedJobsQueued.get(job);
                if(alreadyQueuedAt != null) {
                    if(alreadyQueuedAt <= runJobAt) {
                        Logger.normal(this, "Not re-running as already queued: "+runner+" for "+name);
                        return;
                    } else {
                        // Delete the existing job because the new job will run first.
                        removeQueuedJobInner(job, alreadyQueuedAt);
                    }
                }
            }
            Object o = timedJobsByTime.get(runJobAt);
            if(o == null)
                timedJobsByTime.put(runJobAt, job);
            else if(o instanceof Job)
                timedJobsByTime.put(runJobAt, new Job[]{(Job) o, job});
            else if(o instanceof Job[]) {
                Job[] r = (Job[]) o;
                Job[] jobs = Arrays.copyOf(r, r.length+1);
                jobs[jobs.length - 1] = job;
                timedJobsByTime.put(runJobAt, jobs);
            }
            timedJobsQueued.put(job, runJobAt);
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

	@Override
	public Executor getExecutor() {
		return executor;
	}

	int queuedJobs() {
		synchronized(timedJobsByTime) {
			return timedJobsQueued.size();
		}
	}

    int queuedJobsUniqueTimes() {
        synchronized(timedJobsByTime) {
            return timedJobsByTime.size();
        }
    }

	@Override
	/* Remove a queued job.
	 * @param runnable The job to remove. If this is currently queued, it will be 
	 * removed. The Ticker should not throw if the job is not queued. */
	public void removeQueuedJob(Runnable runnable) {
		Job job = new Job(null, runnable);
		synchronized(timedJobsByTime) {
			Long t = timedJobsQueued.remove(job);
			if(t != null) {
			    removeQueuedJobInner(job, t);
			}
		}
	}

	/** Remove a queued job from the internal structures other than timedJobsQueued. The
	 * caller must check that it is present in timedJobsQueued, remove from that structure, and 
	 * call this method, all inside the timedJobsByTime lock. 
	 * @param job The job to remove.
	 * @param t The time at which is it scheduled.
	 */
	private void removeQueuedJobInner(Job job, Long t) {
        Object o = timedJobsByTime.get(t);
        assert(o != null);
        if(o instanceof Job) {
            assert(o.equals(job));
            timedJobsByTime.remove(t);
        } else {
            Job[] jobs = (Job[]) o;
            if(jobs.length == 1) {
                assert(jobs[0].equals(job));
                timedJobsByTime.remove(t);
            } else {
                Job[] newJobs = new Job[jobs.length-1];
                int x = 0;
                for(Job oldjob : jobs) {
                    if(oldjob.equals(job)) {
                        continue;
                    }
                    newJobs[x++] = oldjob;
                    assert(x != jobs.length); // Must be in jobs array.
                }
                assert(x != 0); // Not duplicated.
                if (x == 1) {
                    timedJobsByTime.put(t, newJobs[0]);
                } else {
                    if(x != newJobs.length)
                        newJobs = Arrays.copyOf(newJobs, x);
                    timedJobsByTime.put(t, newJobs);
                    assert(x == jobs.length-1);
                }
            }
        }
    }
}
