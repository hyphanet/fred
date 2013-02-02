package freenet.support;

import java.util.Hashtable;
import java.util.Timer;
import java.util.TimerTask;

import freenet.node.FastRunnable;

/**
 * Ticker implemented using Timer's.
 * 
 * If deploying this to replace PacketSender, be careful to handle priority changes properly.
 * Hopefully that can be achieved simply by creating at max priority during startup.
 * 
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 *
 */
public class TrivialTicker implements Ticker {

	private final Timer timer = new Timer(true);
	
	private final Executor executor;
	
	private final Hashtable<Runnable, TimerTask> jobs = new Hashtable<Runnable, TimerTask>();
	
	private boolean running = true;
	
	public TrivialTicker(Executor executor) {
		this.executor = executor;
	}
	
	@Override
	public void queueTimedJob(final Runnable job, long offset) {
		TimerTask t = new TimerTask() {
			@Override
			public void run() {
				synchronized(TrivialTicker.this) {
					jobs.remove(job); // We must do this before job.run() in case the job re-schedules itself.
				}
				
				if(job instanceof FastRunnable) {
					job.run();
				} else {
					executor.execute(job, "Delayed task: "+job);
				}
			}
		};
		
		synchronized(this) {
			if(!running)
				return;
		
			timer.schedule(t, offset);
			jobs.put(job, t);
		}
	}

	@Override
	public void queueTimedJob(final Runnable job, final String name, long offset,
			boolean runOnTickerAnyway, boolean noDupes) {
		TimerTask t = new TimerTask() {

			@Override
			public void run() {
				synchronized(TrivialTicker.this) {
					jobs.remove(job); // We must do this before job.run() in case the job re-schedules itself.
				}
				
				if(job instanceof FastRunnable) {
					job.run();
				} else {
					executor.execute(job, name);
				}
			}
			
		};
		
		synchronized(this) {
			if(!running)
				return;
			
			if(noDupes && jobs.containsKey(job))
				return;
			
			timer.schedule(t, offset);
			jobs.put(job, t);
		}
	}
	
	public void cancelTimedJob(final Runnable job) {
		removeQueuedJob(job);
	}
	
	@Override
	public void removeQueuedJob(final Runnable job) {
		synchronized(this) {
			if(!running)
				return;
			
			TimerTask t = jobs.remove(job);
			if(t != null) {
				t.cancel();
			}
		}
	}
	
	/**
	 * Changes the offset of a already-queued job.
	 * If the given job was not queued yet it will be queued nevertheless.
	 */
	public void rescheduleTimedJob(final Runnable job, final String name, long newOffset) {
		synchronized(this) {
			removeQueuedJob(job);
			queueTimedJob(job, name, newOffset, false, false); // Don't dupe-check, we are synchronized
		}
	}
	
	private Thread shutdownThread = null;
	
	public void shutdown() {
		synchronized(this) {
			running = false;
			
			timer.schedule(new TimerTask() {

				@Override
				public void run() {
					// According to the JavaDoc of cancel(), calling it inside a TimerTask guarantees that the task is the last one which is run.
					timer.cancel();
					synchronized(TrivialTicker.this) {
						shutdownThread = Thread.currentThread();
						TrivialTicker.this.notifyAll();
					}
				}
				
			}, 0);
			
			while(shutdownThread == null) {
				try {
					wait();
				} catch (InterruptedException e) { } // Valid to happen due to spurious wakeups
			}
			
			while(shutdownThread.isAlive()) { // Ignore InterruptedExceptions
				try {
					shutdownThread.join();
				} catch (InterruptedException e) { 
					Logger.error(this, "Got an unexpected InterruptedException", e);
				}
			}
		}
	}

	@Override
	public Executor getExecutor() {
		return executor;
	}

}
