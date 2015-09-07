package freenet.support;

import freenet.node.FastRunnable;
import junit.framework.TestCase;

public class PrioritizedTickerTest extends TestCase {
	
	private Executor realExec;
	
	private MyTicker ticker;
	
	private class MyTicker extends PrioritizedTicker {

	    private boolean sleeping;
	    private Object sleepSync = new Object();
	    
        public MyTicker(Executor executor, int portNumber) {
            super(executor, portNumber);
        }
        
        protected void sleep(long sleepTime) throws InterruptedException {
            if(sleepTime == MAX_SLEEP_TIME) {
                synchronized(sleepSync) {
                    sleeping = true;
                    sleepSync.notifyAll();
                }
            }
            super.sleep(sleepTime);
        }
        
        public void waitForSleeping() throws InterruptedException {
            synchronized(sleepSync) {
                while(!sleeping) {
                    sleepSync.wait();
                }
                sleeping = false;
            }
        }
        
        public void waitForIdle() throws InterruptedException {
            while(queuedJobsUniqueTimes() > 0) {
                waitForSleeping();
            }
        }
	    
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		realExec = new PooledExecutor();
		ticker = new MyTicker(realExec, 0);
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
	
    Runnable simpleRunnable2 = new Runnable() {
        
        @Override
        public void run() {
            synchronized(PrioritizedTickerTest.this) {
                runCount+=10;
            }
        }
        
    };
    
    private enum BlockTickerJobState {
        WAITING,
        BLOCKING,
        FINISHED
    }

    /** Allows us to block the Ticker. Because it's a FastRunnable it will be run directly on
     * the Ticker thread itself. But it's not actually fast - it waits! */
    private class BlockTickerJob implements FastRunnable {

        private BlockTickerJobState state = 
                BlockTickerJobState.WAITING;
        private boolean proceed = false;
        
        @Override
        public synchronized void run() {
            state = BlockTickerJobState.BLOCKING;
            notifyAll();
            while(!proceed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
            state = BlockTickerJobState.FINISHED;
            notifyAll();
        }
        
        public synchronized void waitForBlocking() throws InterruptedException {
            while(state != BlockTickerJobState.BLOCKING) {
                wait();
            }
        }
        
        public synchronized void waitForFinished() throws InterruptedException {
            while(state == BlockTickerJobState.FINISHED) {
                wait();
            }
        }
        
        public synchronized void unblockAndWait() throws InterruptedException {
            waitForBlocking();
            proceed = true;
            notifyAll();
            waitForFinished();
        }
        
    }
    
    public void testSimple() throws InterruptedException {
        synchronized(PrioritizedTickerTest.this) {
            runCount = 0;
        }
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        ticker.queueTimedJob(simpleRunnable, 0);
        ticker.waitForIdle();
        synchronized(PrioritizedTickerTest.this) {
            assert(runCount == 1);
        }
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        BlockTickerJob blocker = new BlockTickerJob();
        ticker.queueTimedJob(blocker, "Block the ticker", 0, true, false);
        blocker.waitForBlocking();
        ticker.queueTimedJob(simpleRunnable, "test", 0, true, false);
        assert(ticker.queuedJobs() == 1);
        blocker.unblockAndWait();
        ticker.waitForIdle();
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        synchronized(PrioritizedTickerTest.this) {
            assert(runCount == 2);
        }
    }

    public void testRemove() throws InterruptedException {
        synchronized(PrioritizedTickerTest.this) {
            runCount = 0;
        }
        assert(ticker.queuedJobs() == 0);
        BlockTickerJob blocker = new BlockTickerJob();
        ticker.queueTimedJob(blocker, "Block the ticker", 0, true, false);
        blocker.waitForBlocking();
        ticker.queueTimedJob(simpleRunnable, "test", 0, true, false);
        assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
        ticker.removeQueuedJob(simpleRunnable);
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        synchronized(PrioritizedTickerTest.this) {
            assert(runCount == 0);
        }
        blocker.unblockAndWait();
    }
    
    public void testRemoveTwoInSameMillisecond() throws InterruptedException {
        BlockTickerJob blocker = new BlockTickerJob();
        ticker.queueTimedJob(blocker, "Block the ticker", 0, true, false);
        blocker.waitForBlocking();
        // Use absolute time to ensure they are both in the same millisecond.
        long tRunAt = System.currentTimeMillis();
        ticker.queueTimedJobAbsolute(simpleRunnable, "test1", tRunAt, true, false);
        ticker.queueTimedJobAbsolute(simpleRunnable2, "test2", tRunAt, true, false);
        assert(ticker.queuedJobs() == 2);
        int count = ticker.queuedJobsUniqueTimes();
        assert(count == 1);
        ticker.removeQueuedJob(simpleRunnable);
        assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
        // Remove it again, should not throw or affect other queued job.
        ticker.removeQueuedJob(simpleRunnable);
        assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
        // Remove second job.
        ticker.removeQueuedJob(simpleRunnable2);
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        // Remove second job again, should not throw.
        ticker.removeQueuedJob(simpleRunnable2);
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        blocker.unblockAndWait();
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        synchronized(PrioritizedTickerTest.this) {
            assert(runCount == 0);
        }
    }

	public void testDeduping() throws InterruptedException {
		if(!TestProperty.EXTENSIVE) return; // FIXME unreliable test, only run on -Dtest.extensive=true
		synchronized(PrioritizedTickerTest.this) {
			runCount = 0;
		}
		assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 200, false, true);
		assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 300, false, true);
		assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
		Thread.sleep(220);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 1);
		}
		assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
		Thread.sleep(200);
		synchronized(PrioritizedTickerTest.this) {
			assert(runCount == 1);
		}
		// Now backwards
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 300, false, true);
		assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
		ticker.queueTimedJob(simpleRunnable, "De-dupe test", 200, false, true);
		assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
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
