package freenet.support;

import freenet.support.MemoryLimitedJobRunner.MemoryLimitedChunk;

public abstract class MemoryLimitedJob {
    
    final long initialAllocation;
    
    public MemoryLimitedJob(long initial) {
        this.initialAllocation = initial;
    }
    
    /** Does not affect queueing, only the priority of the thread when it is run. */
    public abstract int getPriority();
    
    /** Start the job.
     * @param chunk The chunk of the scarce resource that has been allocated for this job. Can
     * be released but not added to. Initial size is equal to initialAllocation(). 
     * @return True to free up all resources. Otherwise the job must release() asynchronously. */
    public abstract boolean start(MemoryLimitedChunk chunk);
    
}