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
		
		@Override
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
		Thread.sleep(50);
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
		if(!TestProperty.EXTENSIVE) return; // FIXME unreliable test, only run on -Dtest.extensive=true
		synchronized(PrioritizedTickerTest.this) {
			runCount = 0;
		}
		assert(ticker.queuedJobs() == 0);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 200, false, true);
		assert(ticker.queuedJobs() == 1);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 300, false, true);
		assert(ticker.queuedJobs() == 1);
		Thread.sleep(220);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 1);
		}
		assert(ticker.queuedJobs() == 0);
		Thread.sleep(200);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 1);
		}
		// Now backwards
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 300, false, true);
		assert(ticker.queuedJobs() == 1);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 200, false, true);
		assert(ticker.queuedJobs() == 1);
		Thread.sleep(220);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 2);
		}
		assert(ticker.queuedJobs() == 0);
		Thread.sleep(200);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 2);
		}
		
	}

}
