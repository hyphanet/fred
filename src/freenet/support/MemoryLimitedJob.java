package freenet.support;


public abstract class MemoryLimitedJob {
    
    protected final long initialAllocation;
    
    public MemoryLimitedJob(long initial) {
        this.initialAllocation = initial;
    }
    
    /** All memory limited jobs run at LOW_PRIORITY. This affects queueing. */
    public abstract int getPriority();
    
    /** Start the job. Generally called by MemoryLimitedJobRunner, which schedules jobs within
     * the limited available resource (memory).
     * @param chunk The chunk of the scarce resource that has been allocated for this job. Can
     * be released but not added to. Initial size is equal to initialAllocation(). 
     * @return If this returns true, the caller (MemoryLimitedJobRunner) will call release() on
     * the chunk, and the job must have finished, freeing up all of the memory buffers in use.
     * If it returns false, the job may still be running (asynchronously), and must call 
     * MemoryLimitedJobRunner.MemoryLimitedChunk.release() when it is finished. */
    public abstract boolean start(MemoryLimitedChunk chunk);
    
}
