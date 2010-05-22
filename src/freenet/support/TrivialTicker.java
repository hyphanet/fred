package freenet.support;

import java.util.HashSet;
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
	
	private final HashSet<Runnable> jobs = new HashSet<Runnable>();
	
	private boolean running = true;
	
	public TrivialTicker(Executor executor) {
		this.executor = executor;
	}
	
	public void queueTimedJob(final Runnable job, long offset) {
		synchronized(this) {
			if(!running)
				return;
			
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
			if(!running)
				return;
			
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
	
	public void shutdown() {
		synchronized(this) {
			running = false;
			// TODO: As far as I've understood the javadoc of class Timer, this waits for a currently running job to quit. Verify that by reading the source.
			timer.cancel();
		}
	}

}
