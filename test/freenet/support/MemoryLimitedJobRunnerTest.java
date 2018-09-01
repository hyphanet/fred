package freenet.support;

import junit.framework.TestCase;

import freenet.support.io.NativeThread;

public class MemoryLimitedJobRunnerTest extends TestCase {
    
    final Executor executor = new PooledExecutor();
    
    class SynchronousJob extends MemoryLimitedJob {
        
        private boolean canStart;
        private boolean isStarted;
        private boolean canFinish;
        private boolean isFinished;
        private final Object completionSemaphore;
        
        SynchronousJob(long size, boolean canStart, Object semaphore) {
            super(size);
            this.canStart = canStart;
            canFinish = false;
            completionSemaphore = semaphore;
        }

        @Override
        public int getPriority() {
            return NativeThread.NORM_PRIORITY;
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

        private synchronized void waitForCanFinish() {
            while(!canFinish)
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
        }

        private synchronized void waitForCanStart() {
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
        innerTestQueueingSmallDelayed(1, 512, 1024, false);
    }
    
    public void testQueueingManySmallDelayed() throws InterruptedException {
        innerTestQueueingSmallDelayed(1, 10, 10, false);
        innerTestQueueingSmallDelayed(1, 20, 10, false);
        innerTestQueueingSmallDelayed(1, 512, 512, false);
        innerTestQueueingSmallDelayed(1, 1024, 512, false);
        innerTestQueueingSmallDelayed(1, 20, 1, false);
    }
    
    private void innerTestQueueingSmallDelayed(int JOB_SIZE, int JOB_COUNT, int JOB_LIMIT,
            boolean startLive) throws InterruptedException {
        SynchronousJob[] jobs = new SynchronousJob[JOB_COUNT];
        final Object completion = new Object();
        for(int i=0;i<jobs.length;i++) jobs[i] = new SynchronousJob(JOB_SIZE, startLive, completion);
        // If it all fits, run all the jobs at once. If some are going to be queued, use a small thread limit.
        int maxThreads = JOB_COUNT <= JOB_LIMIT ? JOB_COUNT : 10;
        MemoryLimitedJobRunner runner = new MemoryLimitedJobRunner(JOB_LIMIT, maxThreads, executor, NativeThread.JAVA_PRIORITY_RANGE);
        for(SynchronousJob job : jobs)
            runner.queueJob(job);
        Thread.sleep(100);
        assertTrue(noneFinished(jobs));
        if(!startLive) {
            for(SynchronousJob job : jobs)
                job.setCanStart();
        }
        if(JOB_COUNT <= JOB_LIMIT)
            waitForAllStarted(jobs, completion);
        for(SynchronousJob job : jobs)
            job.setCanFinish();
        waitForAllFinished(jobs, completion);
        waitForZero(runner);
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
    
    private void waitForAllFinished(SynchronousJob[] jobs, Object semaphore) {
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

    private void waitForAllStarted(SynchronousJob[] jobs, Object semaphore) {
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

    private boolean allFinished(SynchronousJob[] jobs) {
        for(SynchronousJob job : jobs) {
            if(!job.isFinished()) return false;
        }
        return true;
    }

    private boolean allStarted(SynchronousJob[] jobs) {
        for(SynchronousJob job : jobs) {
            if(!job.isStarted()) return false;
        }
        return true;
    }

    private boolean noneFinished(SynchronousJob[] jobs) {
        for(SynchronousJob job : jobs) {
            if(job.isFinished()) return false;
        }
        return true;
    }

    class AsynchronousJob extends MemoryLimitedJob {
        
        private boolean canStart;
        private boolean isStarted;
        private boolean canFinish;
        private boolean isFinished;
        private final Object completionSemaphore;
        
        AsynchronousJob(long size, boolean canStart, Object semaphore) {
            super(size);
            this.canStart = canStart;
            canFinish = false;
            completionSemaphore = semaphore;
        }

        @Override
        public int getPriority() {
            return NativeThread.NORM_PRIORITY;
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

        private synchronized void waitForCanFinish() {
            while(!canFinish)
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
        }

        private synchronized void waitForCanStart() {
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
    
    public void testAsyncQueueingSmallDelayed() throws InterruptedException {
        innerTestAsyncQueueingSmallDelayed(1, 10, 20, false);
        innerTestAsyncQueueingSmallDelayed(1, 512, 1024, false);
    }
    
    public void testAsyncQueueingManySmallDelayed() throws InterruptedException {
        innerTestAsyncQueueingSmallDelayed(1, 10, 10, false);
        innerTestAsyncQueueingSmallDelayed(1, 20, 10, false);
        innerTestAsyncQueueingSmallDelayed(1, 512, 512, false);
        innerTestAsyncQueueingSmallDelayed(1, 1024, 512, false);
        innerTestAsyncQueueingSmallDelayed(1, 20, 1, false);
    }
    
    private void innerTestAsyncQueueingSmallDelayed(int JOB_SIZE, int JOB_COUNT, int JOB_LIMIT,
            boolean startLive) throws InterruptedException {
        SynchronousJob[] jobs = new SynchronousJob[JOB_COUNT];
        final Object completion = new Object();
        for(int i=0;i<jobs.length;i++) jobs[i] = new SynchronousJob(JOB_SIZE, startLive, completion);
        // If it all fits, run all the jobs at once. If some are going to be queued, use a small thread limit.
        int maxThreads = JOB_COUNT <= JOB_LIMIT ? JOB_COUNT : 10;
        MemoryLimitedJobRunner runner = new MemoryLimitedJobRunner(JOB_LIMIT, maxThreads, executor, NativeThread.JAVA_PRIORITY_RANGE);
        for(SynchronousJob job : jobs)
            runner.queueJob(job);
        Thread.sleep(100);
        assertTrue(noneFinished(jobs));
        if(!startLive) {
            for(SynchronousJob job : jobs)
                job.setCanStart();
        }
        if(JOB_COUNT <= JOB_LIMIT)
            waitForAllStarted(jobs, completion);
        for(SynchronousJob job : jobs)
            job.setCanFinish();
        waitForAllFinished(jobs, completion);
        waitForZero(runner);
    }

    protected void checkRunner(MemoryLimitedJobRunner runner) {
        long used = runner.used();
        assertTrue(used <= runner.capacity);
        assertTrue(used >= 0);
    }
}
