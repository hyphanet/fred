package freenet.support;

import java.util.HashSet;
import java.util.Timer;
import java.util.TimerTask;

import freenet.node.FastRunnable;
import freenet.node.Ticker;

public class TrivialTicker implements Ticker {

	private final Timer timer = new Timer(true);
	
	private final Executor executor;
	
	private final HashSet<Runnable> jobs = new HashSet<Runnable>();
	
	public TrivialTicker(Executor executor) {
		this.executor = executor;
	}
	
	public void queueTimedJob(final Runnable job, long offset) {
		synchronized(this) {
			jobs.add(job);
		}
		timer.schedule(new TimerTask() {

			@Override
			public void run() {
				try {
					if(job instanceof FastRunnable) {
						job.run();
					} else {
						executor.execute(job, "Delayed task: "+job);
					}
				} finally {
					synchronized(this) {
						jobs.remove(job);
					}
				}
				
			}
			
		}, offset);
	}

	public void queueTimedJob(final Runnable job, final String name, long offset,
			boolean runOnTickerAnyway, boolean noDupes) {
		synchronized(this) {
			if(noDupes && jobs.contains(job)) return;
			jobs.add(job);
		}
		timer.schedule(new TimerTask() {

			@Override
			public void run() {
				try {
					if(job instanceof FastRunnable) {
						job.run();
					} else {
						executor.execute(job, name);
					}
				} finally {
					synchronized(this) {
						jobs.remove(job);
					}
				}
				
			}
			
		}, offset);
	}

}
