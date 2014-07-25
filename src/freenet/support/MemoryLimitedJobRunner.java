package freenet.support;

import java.util.ArrayDeque;
import java.util.Deque;

import freenet.node.PrioRunnable;

/** Start jobs as long as there is sufficient memory (or other limited resource) available, then 
 * queue them. FIXME I bet there is something like this in the standard libraries?
 * @author toad
 */
public class MemoryLimitedJobRunner {
    
    public final long capacity;
    /** The amount of some limited resource that is in use */
    private long counter;
    /** The jobs we can't start yet. FIXME Always FIFO order? Small jobs first? Prioritised even? */
    private final Deque<MemoryLimitedJob> jobs;
    private final Executor executor;
    
    public MemoryLimitedJobRunner(long capacity, Executor executor) {
        this.capacity = capacity;
        this.counter = 0;
        this.jobs = new ArrayDeque<MemoryLimitedJob>();
        this.executor = executor;
        
    }
    
    /** Run the job if the counter is below some threshold, otherwise queue it. */
    public synchronized void queueJob(final MemoryLimitedJob job) {
        if(job.initialAllocation > capacity) throw new IllegalArgumentException("Job size "+job.initialAllocation+" > capacity "+capacity);
        if(counter + job.initialAllocation <= capacity) {
            startJob(job);
        } else {
            jobs.add(job);
        }
    }

    synchronized void deallocate(long size) {
        if(size == 0) return; // Can't do anything, legal no-op.
        if(size < 0) throw new IllegalArgumentException();
        assert(size <= counter);
        counter -= size;
        while(true) {
            MemoryLimitedJob job = jobs.peekFirst();
            if(job == null) return;
            if(job.initialAllocation + counter <= capacity) {
                jobs.removeFirst();
                startJob(job);
            } else return;
        }
    }
    
    private void startJob(final MemoryLimitedJob job) {
        counter += job.initialAllocation;
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

}

