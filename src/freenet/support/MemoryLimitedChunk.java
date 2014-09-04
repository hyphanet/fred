package freenet.support;

/** Represents a chunk of some limited resource, usually an estimate of bytes of memory in use, 
 * which has been allocated to a MemoryLimitedJob. Can be released but not added to.
 * @author toad
 */
public final class MemoryLimitedChunk {
    private final MemoryLimitedJobRunner memoryLimitedJobRunner;
    private long used;
    MemoryLimitedChunk(MemoryLimitedJobRunner memoryLimitedJobRunner, long used) {
        this.memoryLimitedJobRunner = memoryLimitedJobRunner;
        if(used < 0) throw new IllegalArgumentException();
        this.used = used;
    }
    
    /** Should be called when the caller has finished using the resource. Usually when a 
     * MemoryLimitedJob has stopped using a large temporary buffer, and has made it GC'able, that
     * is, there are no more (non-weak) pointers to it. */
    public long release() {
        long released = 0;
        synchronized(this) {
            if(used == 0) return 0;
            released = used;
            used = 0;
        }
        this.memoryLimitedJobRunner.deallocate(released, true);
        return released;
    }

    /** Should be called when the caller is now using a smaller amount of the resource, e.g. we
     * go from a big buffer to a small buffer. Note that this is irreversible. */
    public long release(long amount) {
        boolean finishedThread = false;
        synchronized(this) {
            if(amount > used) throw new IllegalArgumentException("Only have "+used+" in use but asked to release "+amount);
            used -= amount;
            finishedThread = (used == 0);
        }
        this.memoryLimitedJobRunner.deallocate(amount, finishedThread);
        return amount;
    }
    
    MemoryLimitedJobRunner getRunner() {
        return this.memoryLimitedJobRunner;
    }

}