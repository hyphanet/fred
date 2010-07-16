package freenet.support;

import java.util.Hashtable;
import java.util.Timer;
import java.util.TimerTask;

import freenet.node.FastRunnable;
import freenet.node.Ticker;

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
	
	public void queueTimedJob(final Runnable job, long offset) {
		TimerTask t = new TimerTask() {
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
		};
		
		synchronized(this) {
			if(!running)
				return;
		
			timer.schedule(t, offset);
			jobs.put(job, t);
		}
	}

	public void queueTimedJob(final Runnable job, final String name, long offset,
			boolean runOnTickerAnyway, boolean noDupes) {
		TimerTask t = new TimerTask() {

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
			
		};
		
		synchronized(this) {
			if(!running)
				return;
			
			if(noDupes && jobs.contains(job))
				return;
			
			timer.schedule(t, offset);
			jobs.put(job, t);
		}
	}
	
	public void cancelTimedJob(final Runnable job) {
		synchronized(this) {
			if(!running)
				return;
			
			TimerTask t = jobs.get(job);
			if(t != null) {
				t.cancel();
				jobs.remove(t);
			}
		}
	}
	
	/**
	 * Changes the offset of a already-queued job.
	 * If the given job was not queued yet it will be queued nevertheless.
	 */
	public void rescheduleTimedJob(final Runnable job, long newOffset) {
		cancelTimedJob(job);
		queueTimedJob(job, newOffset);
	}
	
	private Thread shutdownThread = null;
	
	public void shutdown() {
		synchronized(this) {
			running = false;
			
			timer.schedule(new TimerTask() {

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

}
