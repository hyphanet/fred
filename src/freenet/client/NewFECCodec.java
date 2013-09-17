package freenet.client;

/** Simple in-memory-only API for FEC encoding/decoding. Does not queue or throttle; see 
 * MemoryLimitedJobRunner for how to deal with that. Caches and creates individual codec engines
 * as needed. */
public abstract class NewFECCodec {
    
    /** Maximum memory usage with the given number of data blocks and check blocks, not including 
     * the blocks themselves. */
    public abstract long maxMemoryOverheadDecode(int dataBlocks, int checkBlocks);
    
    /** Maximum memory usage with the given number of data blocks and check blocks, not including 
     * the blocks themselves. */
    public abstract long maxMemoryOverheadEncode(int dataBlocks, int checkBlocks);
    
    /** Execute a FEC decode. On exiting the function we will have all the data blocks. 
     * @param dataBlocks The byte[]'s for storing the data blocks. Must all be non-null. Which
     * have valid contents is indicated by dataBlocksPresent. When exit this function, they will 
     * be filled with the data blocks in the correct order.
     * @param checkBlocks The byte[]'s for storing the check blocks. Which have valid contents is
     * indicated by checkBlocksPresent.
     * @param dataBlocksPresent Indicates which data blocks are present. When we return, all data
     * blocks will be present.
     * @param checkBlocksPresent Indicates which check blocks are present.
     * @param blockLength The length of any and all blocks. Padding must be handled by the caller
     * if it is necessary.
     */
    public abstract void decode(byte[][] dataBlocks, byte[][] checkBlocks, 
            boolean[] dataBlocksPresent, boolean[] checkBlocksPresent, int blockLength);
    
    /** Execute a FEC encode. On entering, we must have all the data blocks. On exiting, we will
     * have all the check blocks as well. */
    public abstract void encode(byte[][] dataBlocks, byte[][] checkBlocks);

    public static NewFECCodec getInstance(short splitfileType) {
        switch(splitfileType) {
        case Metadata.SPLITFILE_NONREDUNDANT:
            return null;
        case Metadata.SPLITFILE_ONION_STANDARD:
            return new OnionFECCodec();
        default:
            throw new IllegalArgumentException();
        }
    }

}
