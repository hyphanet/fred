package freenet.client.async;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import freenet.client.async.PersistentJobRunner.CheckpointLock;
import freenet.crypt.ChecksumChecker;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.support.Logger;
import freenet.support.MemoryLimitedChunk;
import freenet.support.MemoryLimitedJob;
import freenet.support.io.CountedOutputStream;
import freenet.support.io.NativeThread;
import freenet.support.io.NullOutputStream;
import freenet.support.io.LockableRandomAccessThing.RAFLock;

/** A single segment within a splitfile to be inserted. */
public class SplitFileInserterSegmentStorage {
    
    private final SplitFileInserterStorage parent;

    final int segNo;
    final int dataBlockCount;
    final int crossCheckBlockCount;
    final int checkBlockCount;
    final int totalBlockCount;
    
    /** Has the segment been encoded? If so, all of the check blocks have been written. */
    private boolean encoded;
    private boolean encoding;

    private final int statusLength;
    /** Length of a single key stored on disk. Includes checksum. */
    private final int keyLength;
    
    // FIXME These are refilled by SplitFileInserterCrossSegmentStorage on construction...
    /** For each cross-segment block, the cross-segment responsible */
    private final SplitFileInserterCrossSegmentStorage[] crossSegmentBlockSegments;
    /** For each cross-segment block, the block number within that cross-segment */
    private final int[] crossSegmentBlockNumbers;
    
    private final boolean[] blocksHaveKeys;
    private int blocksWithKeysCounter;
    
    // Choosing keys
    /** Which blocks have been inserted? */
    private final boolean[] blocksInserted;
    /** How many blocks have been inserted? */
    private int blocksInsertedCount;
    
    // These are only used in construction.
    private transient final boolean[] crossDataBlocksAllocated;
    private transient int crossDataBlocksAllocatedCount;
    private transient int crossCheckBlocksAllocatedCount;

    // These are also in parent but we need them here for easy access, especially as we don't want to
    // make the byte[] visible.
    /** For modern splitfiles, the crypto key is the same for every block. */
    private final byte[] splitfileCryptoKey;
    /** Crypto algorithm is the same for every block. */
    private final byte splitfileCryptoAlgorithm;
    
    public SplitFileInserterSegmentStorage(SplitFileInserterStorage parent, int segNo, 
            boolean persistent, int dataBlocks, int checkBlocks, int crossCheckBlocks, int keyLength,
            byte splitfileCryptoAlgorithm, byte[] splitfileCryptoKey) {
        this.parent = parent;
        this.segNo = segNo;
        this.dataBlockCount = dataBlocks;
        this.checkBlockCount = checkBlocks;
        this.crossCheckBlockCount = crossCheckBlocks;
        totalBlockCount = dataBlockCount + crossCheckBlockCount + checkBlockCount;
        this.keyLength = keyLength;
        crossSegmentBlockSegments = new SplitFileInserterCrossSegmentStorage[crossCheckBlocks];
        crossSegmentBlockNumbers = new int[crossCheckBlocks];
        try {
            CountedOutputStream cos = new CountedOutputStream(new NullOutputStream());
            DataOutputStream dos = new DataOutputStream(cos);
            innerStoreStatus(dos);
            dos.close();
            statusLength = (int) cos.written() + parent.checker.checksumLength();
        } catch (IOException e) {
            throw new Error(e); // Impossible
        }
        blocksHaveKeys = new boolean[totalBlockCount];
        this.splitfileCryptoAlgorithm = splitfileCryptoAlgorithm;
        this.splitfileCryptoKey = splitfileCryptoKey;
        crossDataBlocksAllocated = new boolean[dataBlocks + crossCheckBlocks];
        blocksInserted = new boolean[totalBlockCount];
    }

    // These two are only used in construction...
    
    /** Allocate a cross-segment data block. Note that this algorithm must be reproduced exactly 
     * for splitfile compatibility; the Random seed is actually determined by the splitfile metadata.
     * @param seg The cross-segment to allocate a block for.
     * @param random PRNG seeded from the splitfile metadata, which determines which blocks to 
     * allocate in a deterministic manner.
     * @return The data block number allocated.
     */
    int allocateCrossDataBlock(SplitFileInserterCrossSegmentStorage seg, Random random) {
        int size = dataBlockCount;
        if(crossDataBlocksAllocatedCount == size) return -1;
        int x = 0;
        for(int i=0;i<10;i++) {
            x = random.nextInt(size);
            if(!crossDataBlocksAllocated[x]) {
                crossDataBlocksAllocated[x] = true;
                crossDataBlocksAllocatedCount++;
                return x;
            }
        }
        for(int i=0;i<size;i++) {
            x++;
            if(x == size) x = 0;
            if(!crossDataBlocksAllocated[x]) {
                crossDataBlocksAllocated[x] = true;
                crossDataBlocksAllocatedCount++;
                return x;
            }
        }
        throw new IllegalStateException("Unable to allocate cross data block even though have not used all slots up???");
    }

    /** Allocate a cross-segment check block. **Note that this algorithm must be reproduced exactly 
     * for splitfile compatibility**; the Random seed is actually determined by the splitfile metadata.
     * @param seg The cross-segment to allocate a block for.
     * @param random PRNG seeded from the splitfile metadata, which determines which blocks to 
     * allocate in a deterministic manner.
     * @param crossSegmentBlockNumber Block number within the cross-segment.
     * @return The block number allocated (between dataBlockCount and dataBlockCount+crossSegmentCheckBlocks).
     */
    int allocateCrossCheckBlock(SplitFileInserterCrossSegmentStorage seg, Random random, int crossSegmentBlockNumber) {
        if(crossCheckBlocksAllocatedCount == crossCheckBlockCount) return -1;
        int x = crossCheckBlockCount - (1 + random.nextInt(crossCheckBlockCount));
        for(int i=0;i<crossCheckBlockCount;i++) {
            x++;
            if(x == crossCheckBlockCount) x = 0;
            if(crossSegmentBlockSegments[x] == null) {
                crossSegmentBlockSegments[x] = seg;
                crossSegmentBlockNumbers[x] = crossSegmentBlockNumber;
                crossCheckBlocksAllocatedCount++;
                return x;
            }
        }
        throw new IllegalStateException("Unable to allocate cross check block even though have not used all slots up???");
    }

    public void storeStatus() {
        if(!parent.persistent) return;
        DataOutputStream dos;
        try {
            dos = new DataOutputStream(parent.writeChecksummedTo(parent.segmentStatusOffset(segNo), statusLength));
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
        for(boolean b : blocksInserted)
            dos.writeBoolean(b);
    }

    public long storedStatusLength() {
        return statusLength;
    }

    public void writeFixedSettings(DataOutputStream dos) throws IOException {
        dos.writeInt(segNo);
        dos.writeInt(dataBlockCount);
        dos.writeInt(crossCheckBlockCount);
        dos.writeInt(checkBlockCount);
        dos.writeInt(statusLength);
    }
    
    static int getKeyLength(SplitFileInserterStorage parent) {
        return encodeKey(1, 1, ClientCHK.TEST_KEY, parent.hasSplitfileKey(), parent.checker, parent).length;
    }
    
    private static byte[] encodeKey(int segNo, int blockNumber, ClientCHK key, 
            boolean hasSplitfileKey, ChecksumChecker checker, SplitFileInserterStorage parent) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeInt(segNo);
            dos.writeInt(blockNumber);
            dos.writeByte(1); // 1 = present, 0 = not present
            innerWriteKey(key, dos, hasSplitfileKey);
            dos.close();
        } catch (IOException e) {
            throw new Error(e); // Impossible
        }
        byte[] fullBuf = baos.toByteArray();
        byte[] bufNoKeyNumber = Arrays.copyOfRange(fullBuf, 8, fullBuf.length);
        byte[] ret = checker.appendChecksum(bufNoKeyNumber);
        return ret;
    }
    
    static void innerWriteKey(ClientCHK key, DataOutputStream dos, boolean hasSplitfileKey) throws IOException {
        if(hasSplitfileKey) {
            dos.write(key.getRoutingKey());
        } else {
            key.writeRawBinaryKey(dos);
        }
    }

    void clearKeys() throws IOException {
        // Just write 0's. Not valid.
        byte[] buf = new byte[keyLength];
        for(int i=0;i<totalBlockCount;i++) {
            parent.innerWriteSegmentKey(segNo, i, buf);
        }
    }
    
    void setKey(int blockNumber, ClientCHK key) throws IOException {
        try {
            ClientCHK oldKey = readKey(blockNumber);
            if(!oldKey.equals(key))
                throw new IOException("Key for block has changed! Data corruption or bugs in SplitFileInserter code");
        } catch (MissingKeyException e) {
            // Ok.
            writeKey(blockNumber, key);
        }
    }
    
    /** Write a key for a block.
     * @param blockNo The block number. Can be a data block, cross segment check block or check
     * block, in that numerical order.
     * @param key The key to write.
     * @throws IOException If we are unable to write the key. */
    void writeKey(int blockNumber, ClientCHK key) throws IOException {
        byte[] buf = encodeKey(segNo, blockNumber, key, parent.hasSplitfileKey(), parent.checker, parent);
        parent.innerWriteSegmentKey(segNo, blockNumber, buf);
        synchronized(this) {
            if(blocksHaveKeys[blockNumber]) return;
            blocksHaveKeys[blockNumber] = true;
            blocksWithKeysCounter++;
            if(blocksWithKeysCounter != totalBlockCount) return;
        }
        parent.onHasKeys(this);
    }
    
    public synchronized boolean hasKeys() {
        return blocksWithKeysCounter == totalBlockCount;
    }

    public int storedKeysLength() {
        return keyLength * totalBlockCount;
    }
    
    public byte[] readDataBlock(int blockNo) throws IOException {
        assert(blockNo >= 0 && blockNo < dataBlockCount);
        return parent.readSegmentDataBlock(segNo, blockNo);
    }
    
    private void writeCheckBlock(int checkBlockNo, byte[] buf) throws IOException {
        parent.writeSegmentCheckBlock(segNo, checkBlockNo, buf);
    }
    
    public byte[] readCheckBlock(int checkBlockNo) throws IOException {
        assert(checkBlockNo >= 0 && checkBlockNo < checkBlockCount);
        return parent.readSegmentCheckBlock(segNo, checkBlockNo);
    }

    public synchronized void startEncode() {
        if(encoded) return;
        if(encoding) return;
        encoding = true;
        int totalBlockCount = dataBlockCount + checkBlockCount + crossCheckBlockCount;
        long limit = totalBlockCount * CHKBlock.DATA_LENGTH + 
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
                    innerEncode(chunk);
                } finally {
                    if(lock != null) lock.unlock(false, prio);
                    chunk.release();
                    synchronized(this) {
                        encoding = false;
                    }
                    parent.onFinishedEncoding(SplitFileInserterSegmentStorage.this);
                }
                return true;
            }
            
        });
    }

    private void innerEncode(MemoryLimitedChunk chunk) {
        RAFLock lock = null;
        try {
            lock = parent.lockRAF();
            // FIXME encode blocks if earlyEncode
            byte[][] dataBlocks = readDataAndCrossCheckBlocks();
            if(parent.generateKeysOnEncode)
                generateKeys(dataBlocks, 0);
            byte[][] checkBlocks = new byte[checkBlockCount][];
            for(int i=0;i<checkBlocks.length;i++)
                checkBlocks[i] = new byte[CHKBlock.DATA_LENGTH];
            if(dataBlocks == null || checkBlocks == null) return; // Failed with disk error.
            parent.codec.encode(dataBlocks, checkBlocks, new boolean[checkBlocks.length], CHKBlock.DATA_LENGTH);
            for(int i=0;i<checkBlocks.length;i++)
                writeCheckBlock(i, checkBlocks[i]);
            if(parent.generateKeysOnEncode)
                generateKeys(checkBlocks, dataBlockCount + crossCheckBlockCount);
            synchronized(this) {
                encoded = true;
            }
        } catch (IOException e) {
            parent.failOnDiskError(e);
        } catch (Throwable t) {
            Logger.error(this, "Failed: "+t, t);
        } finally {
            if(lock != null) lock.unlock();
        }
    }

    /** Generate keys for each block and record them. 
     * @throws IOException */
    private void generateKeys(byte[][] dataBlocks, int offset) throws IOException {
        for(int i=0;i<dataBlocks.length;i++) {
            setKey(i + offset, encodeBlock(dataBlocks[i]).getClientKey());
        }
    }

    private byte[][] readDataAndCrossCheckBlocks() throws IOException {
        byte[][] data = new byte[dataBlockCount + crossCheckBlockCount][];
        RAFLock lock = parent.lockUnderlying();
        try {
            for(int i=0;i<dataBlockCount;i++)
                data[i] = readDataBlock(i);
        } finally {
            lock.unlock();
        }
        for(int i=0;i<crossCheckBlockCount;i++)
            data[i+dataBlockCount] = readCrossCheckBlock(i);
        return data;
    }

    private byte[] readCrossCheckBlock(int blockNo) throws IOException {
        return crossSegmentBlockSegments[blockNo].
            readCheckBlock(crossSegmentBlockNumbers[blockNo], segNo, blockNo);
    }

    public synchronized boolean isFinishedEncoding() {
        return encoded;
    }

    public ClientCHKBlock encodeBlock(int blockNo) throws IOException {
        byte[] buf = readBlock(blockNo);
        return encodeBlock(buf);
    }

    private byte[] readBlock(int blockNo) throws IOException {
        assert(blockNo >= 0 && blockNo < totalBlockCount);
        if(blockNo < dataBlockCount)
            return readDataBlock(blockNo);
        else if(blockNo < dataBlockCount + crossCheckBlockCount)
            return readCrossCheckBlock(blockNo - dataBlockCount);
        else
            return readCheckBlock(blockNo - (dataBlockCount + crossCheckBlockCount));
    }
    
    ClientCHKBlock encodeBlock(byte[] buf) {
        assert (buf.length == CHKBlock.DATA_LENGTH);
        ClientCHKBlock block;
        try {
            block = ClientCHKBlock.encodeSplitfileBlock(buf, splitfileCryptoKey,
                    splitfileCryptoAlgorithm);
        } catch (CHKEncodeException e) {
            throw new Error(e); // Impossible!
        }
        return block;
    }

    private ClientCHK innerReadKey(DataInputStream dis) throws IOException {
        if(splitfileCryptoKey != null) {
            byte[] routingKey = new byte[32];
            dis.readFully(routingKey);
            return new ClientCHK(routingKey, splitfileCryptoKey, false, splitfileCryptoAlgorithm, 
                    (short)-1);
        } else {
            return new ClientCHK(dis);
        }
    }

    ClientCHK readKey(int blockNumber) throws IOException, MissingKeyException {
        byte[] buf = parent.innerReadSegmentKey(segNo, blockNumber);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(segNo);
        dos.writeInt(blockNumber);
        dos.close();
        byte[] prefix = baos.toByteArray();
        byte[] checkBuf = new byte[prefix.length + buf.length];
        System.arraycopy(prefix, 0, checkBuf, 0, prefix.length);
        int checksumLength = parent.checker.checksumLength();
        System.arraycopy(buf, 0, checkBuf, prefix.length, buf.length - checksumLength);
        byte[] checksum = Arrays.copyOfRange(buf, buf.length - checksumLength, buf.length);
        if(parent.checker.checkChecksum(checkBuf, 0, checkBuf.length, checksum))
            throw new MissingKeyException();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
        byte b = dis.readByte();
        if(b != 1) throw new MissingKeyException();
        return innerReadKey(dis);
    }

    public class MissingKeyException extends Exception {
        
    }
    
    public void onInsertedBlock(int blockNum) {
        synchronized(this) {
            if(blocksInserted[blockNum]) return;
            blocksInserted[blockNum] = true;
            blocksInsertedCount++;
            if(blocksInsertedCount < totalBlockCount) return;
        }
        parent.segmentSucceeded(this);
    }

    public synchronized boolean hasSucceeded() {
        return blocksInsertedCount == totalBlockCount;
    }
    
}
