package freenet.support;

import freenet.node.FastRunnable;
import junit.framework.TestCase;

public class PrioritizedTickerTest extends TestCase {
	
	private WaitableExecutor realExec;
	
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
            if(sleepTime == MAX_SLEEP_TIME) {
                synchronized(sleepSync) {
                    sleeping = false;
                }
            }
        }
        
        public void waitForSleeping() throws InterruptedException {
            synchronized(sleepSync) {
                while(!sleeping) {
                    sleepSync.wait();
                }
            }
        }
        
        public void waitForIdle() throws InterruptedException {
            // Wait until all jobs have been removed from the queue.
            while(queuedJobsUniqueTimes() > 0) {
                waitForSleeping();
            }
            // Wait until the jobs have actually been started off thread or completed on thread.
            waitForSleeping();
        }
	    
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		realExec = new WaitableExecutor(new PooledExecutor());
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
            while(state != BlockTickerJobState.FINISHED) {
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
        realExec.waitForIdle();
        synchronized(PrioritizedTickerTest.this) {
            assertEquals(runCount, 1);
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
        realExec.waitForIdle();
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        synchronized(PrioritizedTickerTest.this) {
            assertEquals(runCount, 2);
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
	    synchronized(PrioritizedTickerTest.this) {
			runCount = 0;
		}
		assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
        BlockTickerJob blocker = new BlockTickerJob();
        ticker.queueTimedJob(blocker, "Block the ticker", 0, true, false);
        blocker.waitForBlocking();
        long runAt = System.currentTimeMillis();
		ticker.queueTimedJobAbsolute(simpleRunnable, "De-dupe test", runAt, true, true);
		assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
		ticker.queueTimedJobAbsolute(simpleRunnable, "De-dupe test", runAt+1, true, true);
		assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
        blocker.unblockAndWait();
        ticker.waitForIdle();
        realExec.waitForIdle();
		synchronized(PrioritizedTickerTest.this) {
		    assertEquals(runCount, 1);
		}
		assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
		// Now backwards
        blocker = new BlockTickerJob();
        ticker.queueTimedJob(blocker, "Block the ticker", 0, true, false);
        blocker.waitForBlocking();
        runAt = System.currentTimeMillis();
        // Note that these will actually be run on the Ticker, and therefore be de-duped.
		ticker.queueTimedJobAbsolute(simpleRunnable, "De-dupe test", runAt+1, false, true);
		assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
		ticker.queueTimedJobAbsolute(simpleRunnable, "De-dupe test", runAt, false, true);
		assert(ticker.queuedJobs() == 1);
        assert(ticker.queuedJobsUniqueTimes() == 1);
        blocker.unblockAndWait();
        ticker.waitForIdle();
        realExec.waitForIdle();
        assert(ticker.queuedJobs() == 0);
        assert(ticker.queuedJobsUniqueTimes() == 0);
		synchronized(PrioritizedTickerTest.this) {
		    assertEquals(runCount, 2);
		}
	}

}
