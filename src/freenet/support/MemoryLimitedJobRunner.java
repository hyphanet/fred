package freenet.support;

import java.util.ArrayDeque;
import java.util.Deque;

import freenet.node.PrioRunnable;

/** Start jobs as long as there is sufficient memory (or other limited resource) available, then 
 * queue them. FIXME I bet there is something like this in the standard libraries?
 * @author toad
 */
public class MemoryLimitedJobRunner {
    
    public long capacity;
    /** The amount of some limited resource that is in use */
    private long counter;
    /** The jobs we can't start yet. FIXME Always FIFO order? Small jobs first? Prioritised even? */
    private final Deque<MemoryLimitedJob> jobs;
    private final Executor executor;
    private int runningThreads;
    private int maxThreads;
    private boolean shutdown;
    
    public MemoryLimitedJobRunner(long capacity, int maxThreads, Executor executor) {
        this.capacity = capacity;
        this.counter = 0;
        this.jobs = new ArrayDeque<MemoryLimitedJob>();
        this.executor = executor;
        this.maxThreads = maxThreads;
        
    }
    
    /** Run the job if the counter is below some threshold, otherwise queue it. Will ignore if 
     * shutting down. */
    public synchronized void queueJob(final MemoryLimitedJob job) {
        if(shutdown) return;
        if(job.initialAllocation > capacity) throw new IllegalArgumentException("Job size "+job.initialAllocation+" > capacity "+capacity);
        if(counter + job.initialAllocation <= capacity && runningThreads < maxThreads) {
            startJob(job);
        } else {
            jobs.add(job);
        }
    }

    synchronized void deallocate(long size, boolean finishedThread) {
        if(size == 0) return; // Can't do anything, legal no-op.
        if(size < 0) throw new IllegalArgumentException();
        assert(size <= counter);
        counter -= size;
        if(finishedThread) {
            runningThreads--;
            if(shutdown) notifyAll();
        }
        maybeStartJobs();
    }
    
    private synchronized void maybeStartJobs() {
        if(shutdown) return;
        while(true) {
            MemoryLimitedJob job = jobs.peekFirst();
            if(job == null) return;
            if(job.initialAllocation + counter <= capacity && runningThreads < maxThreads) {
                jobs.removeFirst();
                startJob(job);
            } else return;
        }
    }
    
    private synchronized void startJob(final MemoryLimitedJob job) {
        counter += job.initialAllocation;
        runningThreads++;
        executor.execute(new PrioRunnable() {

            @Override
            public void run() {
                MemoryLimitedChunk chunk = new MemoryLimitedChunk(MemoryLimitedJobRunner.this, job.initialAllocation);
                if(job.start(chunk))
                    chunk.release();
            }
            
            @Override
            public int getPriority() {
                return job.getPriority();
            }
            
        });
    }

    /** For tests and stats. How much of the scarce resource is used right now? */
    long used() {
        return counter;
    }

    public synchronized void setMaxThreads(int val) {
        this.maxThreads = val;
        maybeStartJobs();
    }

    public synchronized int getMaxThreads() {
        return maxThreads;
    }

    public synchronized long getCapacity() {
        return capacity;
    }

    public synchronized void setCapacity(long val) {
        capacity = val;
        maybeStartJobs();
    }
    
    public synchronized void shutdown() {
        shutdown = true;
    }
    
    public synchronized void waitForShutdown() {
        shutdown = true;
        while(runningThreads > 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                // Ignore.
            }
        }
    }

    public synchronized int getRunningThreads() {
        return runningThreads;
    }

}

