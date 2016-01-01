package freenet.support;

import junit.framework.TestCase;

import freenet.support.io.NativeThread;

public class MemoryLimitedJobRunnerTest extends TestCase {
    
    final Executor executor = new PooledExecutor();
    
    class SynchronousJob extends MemoryLimitedTestJob {
        
        SynchronousJob(long size, boolean canStart, Object semaphore) {
            super(size, canStart, semaphore);
        }

        @Override
        public boolean start(MemoryLimitedChunk chunk) {
            MemoryLimitedJobRunner runner = chunk.getRunner();
            checkRunner(runner);
            waitForCanStart();
            checkRunner(runner);
            synchronized(completionSemaphore) {
                isStarted = true;
                completionSemaphore.notifyAll();
            }
            checkRunner(runner);
            waitForCanFinish();
            checkRunner(runner);
            synchronized(completionSemaphore) {
                isFinished = true;
                completionSemaphore.notifyAll();
            }
            checkRunner(runner);
            return true;
        }
    }

    abstract class MemoryLimitedTestJob extends MemoryLimitedJob {
        protected boolean canStart;
        protected boolean isStarted;
        protected boolean canFinish;
        protected boolean isFinished;
        protected final Object completionSemaphore;

        MemoryLimitedTestJob(long size, boolean canStart, Object semaphore) {
            super(size);
            this.canStart = canStart;
            canFinish = false;
            completionSemaphore = semaphore;
        }

        @Override
        public int getPriority() {
            return NativeThread.NORM_PRIORITY;
        }

        protected synchronized void waitForCanFinish() {
            while(!canFinish)
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
        }

        protected synchronized void waitForCanStart() {
            while(!canStart)
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
        }

        public boolean isFinished() {
            synchronized(completionSemaphore) {
                return isFinished;
            }
        }
        
        public boolean isStarted() {
            synchronized(completionSemaphore) {
                return isStarted;
            }
        }
        
        public synchronized void setCanStart() {
            if(canStart) throw new IllegalStateException();
            canStart = true;
            notify();
        }
        
        public synchronized void setCanFinish() {
            if(canFinish) throw new IllegalStateException();
            canFinish = true;
            notify();
        }
        
    }

    public void testQueueingSmallDelayed() throws InterruptedException {
        innerTestQueueingSmallDelayed(1, 10, 20, false);
        innerTestQueueingSmallDelayed(1, 1024, 1024*2, false);
    }
    
    public void testQueueingManySmallDelayed() throws InterruptedException {
        innerTestQueueingSmallDelayed(1, 10, 10, false);
        innerTestQueueingSmallDelayed(1, 20, 10, false);
        innerTestQueueingSmallDelayed(1, 1024, 1024, false);
        innerTestQueueingSmallDelayed(1, 2048, 1024, false);
        innerTestQueueingSmallDelayed(1, 20, 1, false);
    }
    
    private void innerTestQueueingSmallDelayed(int JOB_SIZE, int JOB_COUNT, int JOB_LIMIT,
            boolean startLive) throws InterruptedException {
        SynchronousJob[] jobs = new SynchronousJob[JOB_COUNT];
        final Object completion = new Object();
        for(int i=0;i<jobs.length;i++) jobs[i] = new SynchronousJob(JOB_SIZE, startLive, completion);
        runJobs(jobs, JOB_COUNT, JOB_LIMIT, completion, startLive);
    }
    
    private void waitForZero(MemoryLimitedJobRunner runner) {
        while(runner.used() > 0) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }
        assertEquals(runner.used(), 0);
    }

    // FIXME start the executor immediately.
    
    private void waitForAllFinished(MemoryLimitedTestJob[] jobs, Object semaphore) {
        synchronized(semaphore) {
            while(true) {
                if(allFinished(jobs)) return;
                try {
                    semaphore.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    private void waitForAllStarted(MemoryLimitedTestJob[] jobs, Object semaphore) {
        synchronized(semaphore) {
            while(true) {
                if(allStarted(jobs)) return;
                try {
                    semaphore.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    private boolean allFinished(MemoryLimitedTestJob[] jobs) {
        for(MemoryLimitedTestJob job : jobs) {
            if(!job.isFinished()) return false;
        }
        return true;
    }

    private boolean allStarted(MemoryLimitedTestJob[] jobs) {
        for(MemoryLimitedTestJob job : jobs) {
            if(!job.isStarted()) return false;
        }
        return true;
    }

    private boolean noneFinished(MemoryLimitedTestJob[] jobs) {
        for(MemoryLimitedTestJob job : jobs) {
            if(job.isFinished()) return false;
        }
        return true;
    }

    class AsynchronousJob extends MemoryLimitedTestJob {
        
        AsynchronousJob(long size, boolean canStart, Object semaphore) {
            super(size, canStart, semaphore);
        }

        @Override
        public boolean start(final MemoryLimitedChunk chunk) {
            final MemoryLimitedJobRunner runner = chunk.getRunner();
            checkRunner(runner);
            waitForCanStart();
            checkRunner(runner);
            synchronized(completionSemaphore) {
                isStarted = true;
                completionSemaphore.notifyAll();
            }
            checkRunner(runner);
            Thread t = new Thread(new Runnable() {

                @Override
                public void run() {
                    checkRunner(runner);
                    waitForCanFinish();
                    checkRunner(runner);
                    synchronized(completionSemaphore) {
                        isFinished = true;
                        completionSemaphore.notifyAll();
                    }
                    checkRunner(runner);
                    assertEquals(chunk.release(), initialAllocation);
                    assertEquals(chunk.release(), 0);
                    checkRunner(runner);
                }
                
            });
            t.start();
            return false;
        }
    }
    
    public void testAsyncQueueingSmallDelayed() throws InterruptedException {
        innerTestAsyncQueueingSmallDelayed(1, 10, 20, false);
        innerTestAsyncQueueingSmallDelayed(1, 1024, 1024*2, false);
    }
    
    public void testAsyncQueueingManySmallDelayed() throws InterruptedException {
        innerTestAsyncQueueingSmallDelayed(1, 10, 10, false);
        innerTestAsyncQueueingSmallDelayed(1, 20, 10, false);
        innerTestAsyncQueueingSmallDelayed(1, 1024, 1024, false);
        innerTestAsyncQueueingSmallDelayed(1, 2048, 1024, false);
        innerTestAsyncQueueingSmallDelayed(1, 20, 1, false);
    }
    
    private void innerTestAsyncQueueingSmallDelayed(int JOB_SIZE, int JOB_COUNT, int JOB_LIMIT,
            boolean startLive) throws InterruptedException {
        AsynchronousJob[] jobs = new AsynchronousJob[JOB_COUNT];
        final Object completion = new Object();
        for(int i=0;i<jobs.length;i++) jobs[i] = new AsynchronousJob(JOB_SIZE, startLive, completion);
        runJobs(jobs, JOB_COUNT, JOB_LIMIT, completion, startLive);
    }

    private void runJobs(MemoryLimitedTestJob[] jobs, int JOB_COUNT, int JOB_LIMIT, Object
            completionLock, boolean startLive) throws InterruptedException {
        // If it all fits, run all the jobs at once. If some are going to be queued, use a small thread limit.
        int maxThreads = JOB_COUNT <= JOB_LIMIT ? JOB_COUNT : 10;
        MemoryLimitedJobRunner runner = new MemoryLimitedJobRunner(JOB_LIMIT, maxThreads, executor, NativeThread.JAVA_PRIORITY_RANGE);
        for(MemoryLimitedTestJob job : jobs)
            runner.queueJob(job);
        Thread.sleep(100);
        assertTrue(noneFinished(jobs));
        if(!startLive) {
            for(MemoryLimitedTestJob job : jobs)
                job.setCanStart();
        }
        if(JOB_COUNT <= JOB_LIMIT)
            waitForAllStarted(jobs, completionLock);
        for(MemoryLimitedTestJob job : jobs)
            job.setCanFinish();
        waitForAllFinished(jobs, completionLock);
        waitForZero(runner);
    }

    protected void checkRunner(MemoryLimitedJobRunner runner) {
        long used = runner.used();
        assertTrue(used <= runner.capacity);
        assertTrue(used >= 0);
    }
}
