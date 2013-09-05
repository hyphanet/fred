package freenet.client;

/** Simple in-memory-only API for FEC encoding/decoding. Does not queue or throttle; see 
 * MemoryLimitedJobRunner for how to deal with that. Caches and creates individual codec engines
 * as needed. */
public interface NewFECCodec {
    
    /** Maximum memory usage with the maximum number of data blocks and check blocks, not including
     * the blocks themselves. */
    public long maxMemoryOverhead();
    
    /** Maximum memory usage with the given number of data blocks and check blocks, not including 
     * the blocks themselves. */
    public long maxMemoryOverhead(int dataBlocks, int checkBlocks);
    
    /** Execute a FEC decode. Null indicates we don't have that block. On exiting the function
     * we will have all the data blocks. */
    public void decode(byte[][] dataBlocks, byte[][] checkBlocks);
    
    /** Execute a FEC encode. On entering, we must have all the data blocks. On exiting, we will
     * have all the check blocks as well. */
    public void encode(byte[][] dataBlocks, byte[][] checkBlocks);

}
