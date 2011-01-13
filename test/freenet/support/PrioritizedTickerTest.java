package freenet.support;

import junit.framework.TestCase;

public class PrioritizedTickerTest extends TestCase {
	
	private Executor realExec;
	
	private PrioritizedTicker ticker;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		realExec = new PooledExecutor();
		ticker = new PrioritizedTicker(realExec, 0);
		ticker.start();
	}

	private int runCount = 0;
	
	Runnable simpleRunnable = new Runnable() {
		
		public void run() {
			synchronized(PrioritizedTickerTest.this) {
				runCount++;
			}
		}
		
	};
	
	public void testSimple() throws InterruptedException {
		synchronized(PrioritizedTickerTest.this) {
			runCount = 0;
		}
		assert(ticker.queuedJobs() == 0);
		ticker.queueTimedJob(simpleRunnable, 0);
		Thread.sleep(10);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 1);
		}
		assert(ticker.queuedJobs() == 0);
		Thread.sleep(100);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 1);
		}
		ticker.queueTimedJob(simpleRunnable, 100);
		assert(ticker.queuedJobs() == 1);
		Thread.sleep(80);
		assert(ticker.queuedJobs() == 1);
		Thread.sleep(200);
		assert(ticker.queuedJobs() == 0);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 2);
		}
	}

	public void testDeduping() throws InterruptedException {
		synchronized(PrioritizedTickerTest.this) {
			runCount = 0;
		}
		assert(ticker.queuedJobs() == 0);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 100, false, true);
		assert(ticker.queuedJobs() == 1);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 150, false, true);
		assert(ticker.queuedJobs() == 1);
		Thread.sleep(110);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 1);
		}
		assert(ticker.queuedJobs() == 0);
		Thread.sleep(100);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 1);
		}
		// Now backwards
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 150, false, true);
		assert(ticker.queuedJobs() == 1);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 100, false, true);
		assert(ticker.queuedJobs() == 1);
		Thread.sleep(110);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 2);
		}
		assert(ticker.queuedJobs() == 0);
		Thread.sleep(100);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 2);
		}
		
	}

}
