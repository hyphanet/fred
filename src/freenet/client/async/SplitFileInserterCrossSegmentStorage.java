package freenet.client.async;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.PersistentJobRunner.CheckpointLock;
import freenet.keys.CHKBlock;
import freenet.support.Logger;
import freenet.support.MemoryLimitedChunk;
import freenet.support.MemoryLimitedJob;
import freenet.support.io.CountedOutputStream;
import freenet.support.io.NativeThread;
import freenet.support.io.NullOutputStream;
import freenet.support.io.LockableRandomAccessThing.RAFLock;

public class SplitFileInserterCrossSegmentStorage {
    
    final SplitFileInserterStorage parent;
    final int segNo;
    final int dataBlockCount;
    final int crossCheckBlockCount;
    final int totalBlocks;
    
    private boolean encoded;
    private boolean encoding;
    
    /** Segment for each block */
    private final SplitFileInserterSegmentStorage[] segments;
    /** Block number within the segment for each block */
    private final int[] blockNumbers;

    // Only used in construction.
    private transient int counter;
    
    private final int statusLength;
    
    public SplitFileInserterCrossSegmentStorage(SplitFileInserterStorage parent, int segNo, 
            boolean persistent, int segLen, int crossCheckBlocks) {
        this.parent = parent;
        this.segNo = segNo;
        this.dataBlockCount = segLen;
        this.crossCheckBlockCount = crossCheckBlocks;
        this.totalBlocks = dataBlockCount + crossCheckBlocks;
        segments = new SplitFileInserterSegmentStorage[totalBlocks];
        blockNumbers = new int[totalBlocks];
        try {
            CountedOutputStream cos = new CountedOutputStream(new NullOutputStream());
            DataOutputStream dos = new DataOutputStream(cos);
            innerStoreStatus(dos);
            dos.close();
            statusLength = (int) cos.written() + parent.checker.checksumLength();
        } catch (IOException e) {
            throw new Error(e); // Impossible
        }
    }

    /** Only used during construction */
    void addDataBlock(SplitFileInserterSegmentStorage seg, int blockNum) {
        segments[counter] = seg;
        blockNumbers[counter] = blockNum;
        counter++;
    }

    public void writeFixedSettings(DataOutputStream dos) throws IOException {
        dos.writeInt(dataBlockCount);
        dos.writeInt(crossCheckBlockCount);
        for(int i=0;i<totalBlocks;i++) {
            dos.writeInt(segments[i].segNo);
            dos.writeInt(blockNumbers[i]);
        }
    }

    public synchronized void startEncode() {
        if(encoded) return;
        if(encoding) return;
        encoding = true;
        long limit = totalBlocks * CHKBlock.DATA_LENGTH + 
            Math.max(parent.codec.maxMemoryOverheadDecode(dataBlockCount, crossCheckBlockCount),
                parent.codec.maxMemoryOverheadEncode(dataBlockCount, crossCheckBlockCount));
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
                } finally {
                    if(lock != null) lock.unlock(false, prio);
                    chunk.release();
                    synchronized(this) {
                        encoding = false;
                    }
                    parent.onFinishedEncoding(SplitFileInserterCrossSegmentStorage.this);
                }
                return true;
            }
            
        });
    }

    /** Encode a segment. Much simpler than fetcher! */
    private void innerDecode(MemoryLimitedChunk chunk) {
        try {
            // FIXME encode blocks if earlyEncode
            byte[][] dataBlocks = readDataBlocks();
            byte[][] checkBlocks = new byte[crossCheckBlockCount][];
            for(int i=0;i<checkBlocks.length;i++)
                checkBlocks[i] = new byte[CHKBlock.DATA_LENGTH];
            if(dataBlocks == null || checkBlocks == null) return; // Failed with disk error.
            parent.codec.encode(dataBlocks, checkBlocks, new boolean[checkBlocks.length], CHKBlock.DATA_LENGTH);
            writeCheckBlocks(checkBlocks);
            synchronized(this) {
                encoded = true;
            }
        } catch (IOException e) {
            parent.failOnDiskError(e);
        }
    }

    private void writeCheckBlocks(byte[][] checkBlocks) throws IOException {
        RAFLock lock = parent.lockRAF();
        try {
            for(int i=0;i<checkBlocks.length;i++)
                writeCheckBlock(i, checkBlocks[i]);
        } finally {
            lock.unlock();
        }
    }

    private void writeCheckBlock(int checkBlockNo, byte[] buf) throws IOException {
        parent.writeCheckBlock(segNo, checkBlockNo, buf);
    }

    /** Read a cross-check block and check consistency 
     * @throws IOException */
    byte[] readCheckBlock(int slotNumberWithinCrossSegment, int segmentNumber, 
            int blockNoWithinSegment) throws IOException {
        assert(blockNumbers[dataBlockCount + slotNumberWithinCrossSegment] == blockNoWithinSegment);
        assert(segments[dataBlockCount + slotNumberWithinCrossSegment].segNo == segmentNumber);
        return parent.readCheckBlock(segNo, slotNumberWithinCrossSegment);
    }

    private byte[][] readDataBlocks() throws IOException {
        RAFLock lock = parent.lockUnderlying();
        try {
            byte[][] data = new byte[dataBlockCount][];
            for(int i=0;i<dataBlockCount;i++) {
                data[i] = segments[i].readDataBlock(blockNumbers[i]);
            }
            return data;
        } finally {
            lock.unlock();
        }
    }

    public synchronized boolean isFinishedEncoding() {
        return encoded;
    }

    public int getAllocatedCrossCheckBlocks() {
        return counter;
    }
    
    public long storedStatusLength() {
        return statusLength;
    }
    
    public void storeStatus() {
        if(!parent.persistent) return;
        DataOutputStream dos;
        try {
            dos = new DataOutputStream(parent.writeChecksummedTo(parent.crossSegmentStatusOffset(segNo), statusLength));
            innerStoreStatus(dos);
        } catch (IOException e) {
            Logger.error(this, "Impossible: "+e, e);
            return;
        }
        try {
            dos.close();
        } catch (IOException e) {
            Logger.error(this, "I/O error writing segment status?: "+e, e);
            parent.failOnDiskError(e);
        }
    }

    private void innerStoreStatus(DataOutputStream dos) throws IOException {
        dos.writeInt(segNo); // To make checksum different.
        dos.writeBoolean(encoded);
    }

}
