package freenet.client.async;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.FECCodec;
import freenet.client.FetchException;
import freenet.client.async.PersistentJobRunner.CheckpointLock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.support.Logger;
import freenet.support.MemoryLimitedChunk;
import freenet.support.MemoryLimitedJob;
import freenet.support.io.NativeThread;
import freenet.support.io.StorageFormatException;

/** Cross-segments are "in parallel" with the main segments, an interlaced Reed-Solomon scheme 
 * similar to that used on CD's, allowing us to fill in blocks from other segments. There are 3
 * "cross-check blocks" in each segment, and therefore 3 in each cross-segment; the rest are data
 * blocks.
 */
public class SplitFileFetcherCrossSegmentStorage {
    
    private static volatile boolean logMINOR;

    static {
        Logger.registerClass(SplitFileFetcherCrossSegmentStorage.class);
    }

    public final int crossSegmentNumber;
    public final SplitFileFetcherStorage parent;
    
    /** Segment for each block */
    private final SplitFileFetcherSegmentStorage[] segments;
    /** Block number within the segment for each block */
    private final int[] blockNumbers;
    /** Whether each block in the cross-segment has been found. Kept up to date when blocks are
     * found in the other segments. However, as in a normal segment, these may not be 100% 
     * accurate! */
    private final boolean[] blocksFound;
    /** Number of data blocks chosen from the various segments. */
    final int dataBlockCount;
    /** Number of check blocks chosen from the various segments. Typically 3. */
    final int crossCheckBlockCount;
    final int totalBlocks;
    private int totalFound;
    /** If true, we are currrently decoding */
    private boolean tryDecode;
    /** If true, the segment has completed */
    private boolean finished;
    private final FECCodec codec;
    /** Used in assigning blocks */
    private int counter;

    SplitFileFetcherCrossSegmentStorage(int segNo, int blocksPerSegment, int crossCheckBlocks, 
            SplitFileFetcherStorage parent, FECCodec codec) {
        this.crossSegmentNumber = segNo;
        this.parent = parent;
        this.dataBlockCount = blocksPerSegment;
        this.crossCheckBlockCount = crossCheckBlocks;
        totalBlocks = dataBlockCount + crossCheckBlocks;
        int totalBlocks = dataBlockCount + crossCheckBlocks;
        this.codec = codec;
        segments = new SplitFileFetcherSegmentStorage[totalBlocks];
        blockNumbers = new int[totalBlocks];
        blocksFound = new boolean[totalBlocks];
    }
    
    /** Called when a segment fetches a block that it believes to be relevant to us */
    public void onFetchedRelevantBlock(SplitFileFetcherSegmentStorage segment, int blockNo) {
        synchronized(this) {
            boolean found = false;
            for(int i=0;i<segments.length;i++) {
                if(segments[i] == segment && blockNumbers[i] == blockNo) {
                    found = true;
                    if(blocksFound[i]) {
                        // Already handled, don't loop.
                        return;
                    }
                    blocksFound[i] = true;
                    totalFound++;
                }
            }
            if(tryDecode || finished) return;
            if(!found) {
                Logger.error(this, "Block "+blockNo+" on "+segment+" not wanted by "+this);
                return;
            }
            if(totalFound < dataBlockCount) {
                Logger.normal(this, "Not decoding "+this+" : found "+totalFound+" blocks of "+dataBlockCount+" (total "+segments.length+")");
                return;
            }
            tryDecodeOrEncode();
        }
    }
    
    private synchronized void tryDecodeOrEncode() {
        if(finished) return;
        if(tryDecode) return;
        long limit = totalBlocks * CHKBlock.DATA_LENGTH + 
            Math.max(parent.fecCodec.maxMemoryOverheadDecode(dataBlockCount, crossCheckBlockCount),
                    parent.fecCodec.maxMemoryOverheadEncode(dataBlockCount, crossCheckBlockCount));
        final int prio = NativeThread.LOW_PRIORITY;
        parent.memoryLimitedJobRunner.queueJob(new MemoryLimitedJob(limit) {
            
            @Override
            public int getPriority() {
                return prio;
            }
            
            @Override
            public boolean start(MemoryLimitedChunk chunk) {
                CheckpointLock lock = null;
                try {
                    lock = parent.jobRunner.lock();
                    innerDecode(chunk);
                } catch (IOException e) {
                    Logger.error(this, "Failed to decode "+this+" because of disk error: "+e, e);
                    parent.failOnDiskError(e);
                } finally {
                    if(lock != null) lock.unlock(false, prio);
                    chunk.release();
                    synchronized(this) {
                        tryDecode = false;
                    }
                }
                return true;
            }
            
        });
        tryDecode = true;
    }
    
    /** Attempt FEC decoding. Check blocks before decoding in case there is disk corruption. Check
     * the new decoded blocks afterwards to ensure reproducible behaviour. */
    private void innerDecode(MemoryLimitedChunk chunk) throws IOException {
        if(logMINOR) Logger.minor(this, "Trying to decode "+this+" for "+parent);
        synchronized(this) {
            if(finished) return;
        }
        
        // readAllBlocks does most of the housekeeping for us, see below...
        byte[][] dataBlocks = readBlocks(false);
        byte[][] checkBlocks = readBlocks(true);
        if(dataBlocks == null || checkBlocks == null) return; // Failed with disk error.
        
        // Original status.
        boolean[] dataBlocksFound = wasNonNullFill(dataBlocks);
        boolean[] checkBlocksFound = wasNonNullFill(checkBlocks);

        int realTotalDataBlocks = count(dataBlocksFound);
        int realTotalCrossCheckBlocks = count(checkBlocksFound);
        int realTotalFound = realTotalDataBlocks + realTotalCrossCheckBlocks;
        
        if(realTotalFound < dataBlockCount) {
            // Not finished yet.
            return;
        }
        
        boolean decoded = false;
        boolean encoded = false;
        
        if(realTotalDataBlocks < dataBlockCount) {
            // Decode.
            codec.decode(dataBlocks, checkBlocks, dataBlocksFound, checkBlocksFound, 
                    CHKBlock.DATA_LENGTH);
            for(int i=0;i<dataBlockCount;i++) {
                if(!dataBlocksFound[i]) {
                    checkDecodedBlock(i, dataBlocks[i]);
                    dataBlocksFound[i] = true;
                }
            }
        }
        
        if(realTotalCrossCheckBlocks < crossCheckBlockCount) {
            // Decode.
            codec.encode(dataBlocks, checkBlocks, checkBlocksFound, CHKBlock.DATA_LENGTH);
            for(int i=0;i<crossCheckBlockCount;i++) {
                if(!checkBlocksFound[i]) {
                    checkDecodedBlock(i+dataBlockCount, checkBlocks[i]);
                }
            }
        }
        
        synchronized(this) {
            finished = true;
        }
        
        Logger.error(this, "Completed a cross-segment: decoded="+decoded+" encoded="+encoded);
        parent.finishedEncoding(this);
    }


    private void checkDecodedBlock(int i, byte[] data) {
        ClientCHK key = getKey(i);
        if(key == null) {
            Logger.error(this, "Key not found");
            failOffThread(new FetchException(FetchException.INTERNAL_ERROR, "Key not found"));
            return;
        }
        ClientCHKBlock block = encodeBlock(key, data);
        String decoded = i >= dataBlockCount ? "Encoded" : "Decoded";
        if(block == null || !key.getNodeCHK().equals(block.getKey())) {
            Logger.error(this, decoded+" cross-segment block "+i+" failed!");
            failOffThread(new FetchException(FetchException.SPLITFILE_DECODE_ERROR, decoded+" cross-segment block does not match expected key"));
            return;
        } else {
            reportBlockToSegmentOffThread(i, key, block);
        }
    }

    private ClientCHKBlock encodeBlock(ClientCHK key, byte[] data) {
        try {
            return ClientCHKBlock.encodeSplitfileBlock(data, key.getCryptoKey(), key.getCryptoAlgorithm());
        } catch (CHKEncodeException e) {
            return null;
        }
    }

    private void reportBlockToSegmentOffThread(final int blockNo, final ClientCHK key, 
            final ClientCHKBlock block) {
        parent.jobRunner.queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                try {
                    // FIXME CPU USAGE Add another API to the segment to avoid re-decoding.
                    boolean success = segments[blockNo].onGotKey(key.getNodeCHK(), block.getBlock());
                    if(success)
                        Logger.error(this, "Successfully decoded cross-segment block");
                    else
                        Logger.error(this, "Decoded cross-segment block but not wanted by segment");
                } catch (IOException e) {
                    parent.failOnDiskError(e);
                    return true;
                }
                return false;
            }
        });
    }

    private void failOffThread(final FetchException e) {
        parent.jobRunner.queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                parent.fail(e);
                return true;
            }
            
        });
    }

    private void failDiskOffThread(final IOException e) {
        parent.jobRunner.queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                parent.failOnDiskError(e);
                return true;
            }
            
        });
    }

    private ClientCHK getKey(int i) {
        return segments[i].getKey(blockNumbers[i]);
    }

    private static boolean[] wasNonNullFill(byte[][] blocks) {
        boolean[] nonNulls = new boolean[blocks.length];
        for(int i=0;i<blocks.length;i++) {
            if(blocks[i] == null) {
                blocks[i] = new byte[CHKBlock.DATA_LENGTH];
            } else {
                nonNulls[i] = true;
            }
        }
        return nonNulls;
    }

    /** Read all blocks from all segments, checking the contents of the block against each key.
     * @param If false, read data blocks, if true, read check blocks. (The FEC code takes separate
     * arrays).
     * @return An array of blocks, in the correct order. Each element is either a valid block or
     * null if the block is invalid or hasn't been fetched yet. Will tell the ordinary segment if 
     * the block is bogus. Will also update our blocksFound. */
    
    private byte[][] readBlocks(boolean checkBlocks) {
        int start = checkBlocks ? dataBlockCount : 0;
        int end = checkBlocks ? totalBlocks : dataBlockCount;
        byte[][] blocks = new byte[end-start][];
        for(int i=start;i<end;i++) {
            try {
                byte[] block = segments[i].checkAndGetBlockData(blockNumbers[i]);
                blocks[i-start] = block;
                synchronized(this) {
                    if(block != null) {
                        if(!blocksFound[i]) totalFound++;
                        blocksFound[i] = true;
                    } else {
                        if(blocksFound[i]) totalFound--;
                        blocksFound[i] = false;
                    }
                }
            } catch (IOException e) {
                failDiskOffThread(e);
                return null;
            }
        }
        return blocks;
    }
    
    private static int count(boolean[] array) {
        int total = 0;
        for(boolean b : array)
            if(b) total++;
        return total;
    }

    public void addDataBlock(SplitFileFetcherSegmentStorage seg, int blockNum) {
        segments[counter] = seg;
        blockNumbers[counter] = blockNum;
        counter++;
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    public void writeFixedMetadata(DataOutputStream dos) throws IOException {
        dos.writeInt(dataBlockCount);
        dos.writeInt(crossCheckBlockCount);
        for(int i=0;i<totalBlocks;i++) {
            dos.writeInt(segments[i].segNo);
            dos.writeInt(blockNumbers[i]);
        }
    }
    
    public SplitFileFetcherCrossSegmentStorage(SplitFileFetcherStorage parent, int segNo,
            DataInputStream dis) throws IOException, StorageFormatException {
        this.parent = parent;
        this.crossSegmentNumber = segNo;
        this.codec = parent.fecCodec;
        this.dataBlockCount = dis.readInt();
        this.crossCheckBlockCount = dis.readInt();
        this.totalBlocks = dataBlockCount + crossCheckBlockCount;
        blocksFound = new boolean[totalBlocks];
        segments = new SplitFileFetcherSegmentStorage[totalBlocks];
        blockNumbers = new int[totalBlocks];
        for(int i=0;i<totalBlocks;i++) {
            int readSeg = dis.readInt();
            if(readSeg < 0 || readSeg >= parent.segments.length)
                throw new StorageFormatException("Invalid segment number "+readSeg);
            SplitFileFetcherSegmentStorage segment = parent.segments[readSeg];
            int blockNo = dis.readInt();
            if(blockNo < 0 || blockNo >= segment.totalBlocks())
                throw new StorageFormatException("Invalid block number "+blockNo+" for segment "+segment.segNo);
        }
    }

    /** Check for blocks and try to decode. */
    public void restart() {
        synchronized(this) {
            if(finished) return;
        }
        readBlocks(false);
        readBlocks(true);
        synchronized(this) {
            if(totalBlocks < dataBlockCount) return;
            tryDecodeOrEncode();
        }
    }

}
