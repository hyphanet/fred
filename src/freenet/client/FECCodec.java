package freenet.client;

import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata.SplitfileAlgorithm;

/** Simple in-memory-only API for FEC encoding/decoding. Does not queue or throttle; see 
 * MemoryLimitedJobRunner for how to deal with that. Caches and creates individual codec engines
 * as needed. */
public abstract class FECCodec {
    
    public static final long MIN_MEMORY_ALLOCATION = 8*1024*1024+256*1024;
    public static final int MAX_TOTAL_BLOCKS_PER_SEGMENT = 256;
    
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
     * @param dataBlocksPresent Indicates which data blocks were present before decoding. (Will
     * not be changed by this function).
     * @param checkBlocksPresent Indicates which check blocks are present before decoding. (Will
     * not be changed by this function).
     * @param blockLength The length of any and all blocks. Padding must be handled by the caller
     * if it is necessary.
     */
    public abstract void decode(byte[][] dataBlocks, byte[][] checkBlocks, 
            boolean[] dataBlocksPresent, boolean[] checkBlocksPresent, int blockLength);
    
    /** Execute a FEC encode. On entering, we must have all the data blocks. On exiting, we will
     * have all the check blocks as well.
     * @param dataBlocks All the data blocks, which all have valid contents.
     * @param checkBlocks The byte[]'s for storing the encoded check blocks. Must all be non-null.
     * @param checkBlocksPresent Indicates which check blocks have already been encoded. */
    public abstract void encode(byte[][] dataBlocks, byte[][] checkBlocks, boolean[] checkBlocksPresent,
            int blockLength);

    public static FECCodec getInstance(SplitfileAlgorithm splitfileType) {
        switch(splitfileType) {
        case NONREDUNDANT:
            return null;
        case ONION_STANDARD:
            return new OnionFECCodec();
        default:
            throw new IllegalArgumentException();
        }
    }

    /** Get the recommended number of check blocks per segment for a given number of data blocks 
     * for a given compatibility mode.
     * @param dataBlocks The number of data blocks per segment.
     * @param cmode The compatibility mode (so we can exactly mimic the behaviour of older builds
     * when reinserting files).
     */
    public abstract int getCheckBlocks(int dataBlocks, CompatibilityMode cmode);

}
