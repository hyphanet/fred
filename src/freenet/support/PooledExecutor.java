/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.ArrayList;

/**
 * Pooled Executor implementation. Create a thread when we need one, let them die
 * after 5 minutes of inactivity.
 * @author toad
 */
public class PooledExecutor implements Executor {

	private final ArrayList runningThreads /* <MyThread> */ = new ArrayList();
	private final ArrayList waitingThreads /* <MyThread> */ = new ArrayList();
	long threadCounter = 0;
	
	/** Maximum time a thread will wait for a job */
	static final int TIMEOUT = 5*60*1000;
	
	public void execute(Runnable job, String jobName) {
		while(true) {
			MyThread t;
			boolean mustStart = false;
			synchronized(this) {
				if(!waitingThreads.isEmpty()) {
					t = (MyThread) waitingThreads.remove(waitingThreads.size()-1);
				} else {
					t = new MyThread("Pooled thread "+(threadCounter++));
					t.setDaemon(true);
					mustStart = true;
				}
			}
			synchronized(t) {
				if(!t.alive) continue;
				if(t.nextJob != null) continue;
				t.nextJob = job;
				if(!mustStart)
					// It is possible that we could get a wierd race condition with
					// notify()/wait() signalling on a thread being used by higher
					// level code. So we'd best use notifyAll().
					t.notifyAll();
			}
			t.setName(jobName);
			if(mustStart) {
				t.start();
				synchronized(this) {
					runningThreads.add(t);
				}
			}
			return;
		}
	}

	class MyThread extends Thread {
		
		final String defaultName;
		boolean alive = true;
		Runnable nextJob;
		
		public MyThread(String defaultName) {
			super(defaultName);
			this.defaultName = defaultName;
		}

		public void run() {
			while(true) {
				Runnable job;
				
				synchronized(this) {
					job = nextJob;
					nextJob = null;
				}
				
				if(job == null) {
					synchronized(PooledExecutor.this) {
						waitingThreads.add(this);
					}
					synchronized(this) {
						if(nextJob == null) {
							this.setName(defaultName);
							try {
								wait(TIMEOUT);
							} catch (InterruptedException e) {
								// Ignore
							}
						}
						job = nextJob;
						nextJob = null;
						if(job == null) {
							alive = false;
							// execute() won't give us another job if alive = false
						}
					}
					synchronized(PooledExecutor.this) {
						waitingThreads.remove(this);
						if(!alive) {
							runningThreads.remove(this);
							return;
						}
					}
				}
				
				// Run the job
				try {
					job.run();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" running job "+job, t);
				}
			}
		}
		
	}
	
}
