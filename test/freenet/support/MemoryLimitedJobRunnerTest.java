package freenet.support;

import junit.framework.TestCase;

import freenet.support.MemoryLimitedJobRunner.MemoryLimitedChunk;
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
            waitForCanStart();
            synchronized(completionSemaphore) {
                isStarted = true;
                completionSemaphore.notifyAll();
            }
            waitForCanFinish();
            synchronized(completionSemaphore) {
                isFinished = true;
                completionSemaphore.notifyAll();
            }
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
        MemoryLimitedJobRunner runner = new MemoryLimitedJobRunner(JOB_LIMIT, executor);
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

    // FIXME test asynchronous jobs.

}
