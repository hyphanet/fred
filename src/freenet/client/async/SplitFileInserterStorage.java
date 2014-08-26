package freenet.client.async;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.ClientMetadata;
import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.Metadata;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.InsertException;
import freenet.client.async.SplitFileInserterSegmentStorage.MissingKeyException;
import freenet.crypt.ChecksumChecker;
import freenet.crypt.HashResult;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHK;
import freenet.node.KeysFetchingLocally;
import freenet.node.SendableInsert;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.MemoryLimitedJobRunner;
import freenet.support.Ticker;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.LockableRandomAccessThing;
import freenet.support.io.LockableRandomAccessThing.RAFLock;
import freenet.support.io.LockableRandomAccessThingFactory;
import freenet.support.io.NullBucket;
import freenet.support.math.MersenneTwister;

/**
 * Similar to SplitFileFetcherStorage. The status of a splitfile insert,
 * including the encoded check and cross-check blocks, is stored in a
 * RandomAccessThing separate to that containing the data itself. This class is
 * only concerned with encoding, storage etc, and should have as few
 * dependencies on the runtime as possible, partly so that it can be tested in
 * isolation. Actual details of inserting the blocks will be kept on
 * SplitFileInserter.
 * 
 * CONTENTS OF FILE:
 * 
 * Magic, version etc. Basic settings. Settings for each segment. Settings for
 * each cross-segment (including block lists). Padded last block (if necessary)
 * Global status (Padding to a 4096 byte boundary) Cross-check blocks (by
 * cross-segment). Check blocks Segment status Segment key list - For each
 * segment, every key is stored separately with a checksum.
 * 
 * Intentionally not as robust as SplitFileFetcherStorage: We don't keep
 * checksums for the blocks. Inserts generally are relatively short-lived,
 * because if they're not the data will have fallen out by the time it finishes.
 * 
 * FIXME do we want to wait until it has finished encoding everything before
 * inserting anything?
 * 
 * @author toad
 * 
 */
public class SplitFileInserterStorage {

    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;
    static {
        Logger.registerClass(SplitFileInserterStorage.class);
    }

    /** The original file to upload */
    private final LockableRandomAccessThing originalData;
    /** The RAF containing check blocks, status etc. */
    private final LockableRandomAccessThing raf;
    /** Is the request persistent? */
    final boolean persistent;
    private final SplitFileInserterStorageCallback callback;

    final SplitFileInserterSegmentStorage[] segments;
    final SplitFileInserterCrossSegmentStorage[] crossSegments;

    final int totalDataBlocks;
    final int totalCheckBlocks;

    /** FEC codec used to encode check and cross-check blocks */
    final FECCodec codec;

    /**
     * Length in bytes of the data being uploaded, i.e. the original file,
     * ignoring padding, check blocks etc.
     */
    private final long dataLength;

    // These are kept for creating Metadata etc.
    /** MIME type etc. */
    private final ClientMetadata clientMetadata;
    /** Is the splitfile metadata? */
    private final boolean isMetadata;
    /** Compression codec that should be used to decompress the data. We do not
     * do the compression here but we need it to generate the Metadata. */
    private final COMPRESSOR_TYPE compressionCodec;
    /** Length of the file after decompression. */
    private final long decompressedLength;
    /** For reinserting old splitfiles etc. */
    private final CompatibilityMode cmode;
    private final byte[] hashThisLayerOnly;
    private final ARCHIVE_TYPE archiveType;
    
    // Top level stuff
    private final HashResult[] hashes;
    private final boolean topDontCompress;
    private final int topRequiredBlocks;
    private final int topTotalBlocks;
    private final long origDataSize;
    private final long origCompressedDataSize;

    /** Type of splitfile */
    private final short splitfileType;
    /** Nominal number of data blocks per segment. */
    private final int segmentSize;
    /** Nominal number of check blocks per segment. */
    private final int checkSegmentSize;
    /**
     * Number of segments which have 1 fewer block than segmentSize. Not
     * necessarily valid for very old compatibility modes.
     */
    private final int deductBlocksFromSegments;
    /**
     * Number of cross-check blocks per segment and therefore also per
     * cross-segment.
     */
    private final int crossCheckBlocks;

    /** For modern splitfiles, the crypto key is the same for every block. */
    private final byte[] splitfileCryptoKey;
    /** Crypto algorithm is the same for every block. */
    private final byte splitfileCryptoAlgorithm;
    /**
     * If true, the splitfile crypto key must be included in the metadata. If
     * false, it was auto-generated so can be left implicit.
     */
    private final boolean specifySplitfileKeyInMetadata;

    // Misc settings
    final ChecksumChecker checker;
    /** Length of a key as stored on disk */
    private final int keyLength;
    private final int maxRetries;
    private final int consecutiveRNFsCountAsSuccess;

    // System utilities.
    final MemoryLimitedJobRunner memoryLimitedJobRunner;
    final PersistentJobRunner jobRunner;
    final Ticker ticker;

    /**
     * True if the size of the file is not exactly divisible by one block. If
     * so, we have the last block, after padding, stored in raf. (This means we
     * can change the padding algorithm slightly more easily)
     */
    private final boolean hasPaddedLastBlock;

    /** Status. Generally depends on the status of the individual segments...
     * Not persisted: Can be deduced from the state of the segments, except for the last 3 states, 
     * which are only used during completion (we don't keep the storage around once we're 
     * finished). */
    enum Status {
        NOT_STARTED, STARTED, ENCODED_CROSS_SEGMENTS, ENCODED, GENERATING_METADATA, SUCCEEDED, FAILED
    }

    private Status status;
    private final FailureCodeTracker errors;
    private boolean overallStatusDirty;
    
    // Not persisted, only used briefly during completion
    private InsertException failing;

    // These are kept here so we can set them in the main constructor after
    // we've constructed the segments.

    /** Offset in originalData to the start of each data segment */
    private final long[] underlyingOffsetDataSegments;
    private final long offsetPaddedLastBlock;
    private final long offsetOverallStatus;
    private final long[] offsetCrossSegmentBlocks;
    private final long[] offsetSegmentCheckBlocks;
    private final long[] offsetSegmentStatus;
    private final long[] offsetCrossSegmentStatus;
    private final long[] offsetSegmentKeys;

    private final int overallStatusLength;
    
    /**
     * Create a SplitFileInserterStorage.
     * 
     * @param originalData
     *            The original data as a RandomAccessThing. We need to be able
     *            to read single blocks.
     * @param rafFactory
     *            A factory (persistent or not as appropriate) used to create
     *            the temporary RAF storing check blocks etc.
     * @param bf
     *            A temporary Bucket factory used for temporarily storing the
     *            cross-segment settings.
     * @throws IOException
     * @throws InsertException
     */
    public SplitFileInserterStorage(LockableRandomAccessThing originalData,
            long decompressedLength, SplitFileInserterStorageCallback callback,
            COMPRESSOR_TYPE compressionCodec, ClientMetadata meta, boolean isMetadata, 
            ARCHIVE_TYPE archiveType, LockableRandomAccessThingFactory rafFactory, 
            boolean persistent, InsertContext ctx, byte splitfileCryptoAlgorithm, 
            byte[] splitfileCryptoKey, byte[] hashThisLayerOnly, HashResult[] hashes, 
            BucketFactory bf, ChecksumChecker checker, Random random,
            MemoryLimitedJobRunner memoryLimitedJobRunner, PersistentJobRunner jobRunner,
            Ticker ticker, KeysFetchingLocally keysFetching, 
            boolean topDontCompress, int topRequiredBlocks, int topTotalBlocks, 
            long origDataSize, long origCompressedDataSize) throws IOException, InsertException {
        this.originalData = originalData;
        this.callback = callback;
        this.persistent = persistent;
        dataLength = originalData.size();
        if (dataLength > ((long) Integer.MAX_VALUE) * CHKBlock.DATA_LENGTH)
            throw new InsertException(InsertException.TOO_BIG);
        totalDataBlocks = (int) ((dataLength + CHKBlock.DATA_LENGTH - 1) / CHKBlock.DATA_LENGTH);
        this.decompressedLength = decompressedLength;
        this.compressionCodec = compressionCodec;
        this.clientMetadata = meta;
        this.checker = checker;
        this.memoryLimitedJobRunner = memoryLimitedJobRunner;
        this.jobRunner = jobRunner;
        this.hashes = hashes;
        this.isMetadata = isMetadata;
        this.archiveType = archiveType;
        this.hashThisLayerOnly = hashThisLayerOnly;
        this.topDontCompress = topDontCompress;
        this.topRequiredBlocks = topRequiredBlocks;
        this.topTotalBlocks = topTotalBlocks;
        this.origDataSize = origDataSize;
        this.origCompressedDataSize = origCompressedDataSize;
        this.maxRetries = ctx.maxInsertRetries;
        this.errors = new FailureCodeTracker(true);
        this.ticker = ticker;

        // Work out how many blocks in each segment, crypto keys etc.
        // Complicated by back compatibility, i.e. the need to be able to
        // reinsert old splitfiles.
        // FIXME consider getting rid of support for very old splitfiles.

        int segs;
        cmode = ctx.getCompatibilityMode();
        if (cmode == CompatibilityMode.COMPAT_1250_EXACT) {
            segs = (totalDataBlocks + 128 - 1) / 128;
            segmentSize = 128;
            deductBlocksFromSegments = 0;
        } else {
            if (cmode == CompatibilityMode.COMPAT_1251) {
                // Max 131 blocks per segment.
                segs = (totalDataBlocks + 131 - 1) / 131;
            } else {
                // Algorithm from evanbd, see bug #2931.
                if (totalDataBlocks > 520) {
                    segs = (totalDataBlocks + 128 - 1) / 128;
                } else if (totalDataBlocks > 393) {
                    // maxSegSize = 130;
                    segs = 4;
                } else if (totalDataBlocks > 266) {
                    // maxSegSize = 131;
                    segs = 3;
                } else if (totalDataBlocks > 136) {
                    // maxSegSize = 133;
                    segs = 2;
                } else {
                    // maxSegSize = 136;
                    segs = 1;
                }
            }
            int segSize = (totalDataBlocks + segs - 1) / segs;
            if (ctx.splitfileSegmentDataBlocks < segSize) {
                segs = (totalDataBlocks + ctx.splitfileSegmentDataBlocks - 1)
                        / ctx.splitfileSegmentDataBlocks;
                segSize = (totalDataBlocks + segs - 1) / segs;
            }
            segmentSize = segSize;
            if (cmode == CompatibilityMode.COMPAT_CURRENT
                    || cmode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal()) {
                // Even with basic even segment splitting, it is possible for
                // the last segment to be a lot smaller than the rest.
                // So drop a single data block from each of the last
                // [segmentSize-lastSegmentSize] segments instead.
                // Hence all the segments are within 1 block of segmentSize.
                int lastSegmentSize = totalDataBlocks - (segmentSize * (segs - 1));
                deductBlocksFromSegments = segmentSize - lastSegmentSize;
            } else {
                deductBlocksFromSegments = 0;
            }
        }

        int crossCheckBlocks = 0;

        // Cross-segment splitfile redundancy becomes useful at 20 segments.
        if (segs >= 20
                && (cmode == CompatibilityMode.COMPAT_CURRENT || cmode.ordinal() >= CompatibilityMode.COMPAT_1255
                        .ordinal())) {
            // The optimal number of cross-check blocks per segment (and per
            // cross-segment since there are the same number of cross-segments
            // as segments) is 3.
            crossCheckBlocks = 3;
        }

        this.crossCheckBlocks = crossCheckBlocks;

        this.splitfileType = ctx.splitfileAlgorithm;
        this.codec = FECCodec.getInstance(splitfileType);

        checkSegmentSize = codec.getCheckBlocks(segmentSize + crossCheckBlocks, cmode);

        this.splitfileCryptoAlgorithm = splitfileCryptoAlgorithm;
        if (splitfileCryptoKey != null) {
            this.splitfileCryptoKey = splitfileCryptoKey;
            specifySplitfileKeyInMetadata = true;
        } else if (cmode == CompatibilityMode.COMPAT_CURRENT
                || cmode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal()) {
            if (hashThisLayerOnly != null) {
                this.splitfileCryptoKey = Metadata.getCryptoKey(hashThisLayerOnly);
            } else {
                this.splitfileCryptoKey = Metadata.getCryptoKey(hashes);
            }
            specifySplitfileKeyInMetadata = false;
        } else {
            this.splitfileCryptoKey = null;
            specifySplitfileKeyInMetadata = false;
        }

        int totalCheckBlocks = 0;
        int checkTotalDataBlocks = 0;
        underlyingOffsetDataSegments = new long[segs];
        keyLength = SplitFileInserterSegmentStorage.getKeyLength(this);
        this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
        segments = makeSegments(segmentSize, segs, totalDataBlocks, crossCheckBlocks,
                deductBlocksFromSegments, persistent, splitfileCryptoAlgorithm, splitfileCryptoKey,
                cmode, random, keysFetching, consecutiveRNFsCountAsSuccess);
        for (SplitFileInserterSegmentStorage segment : segments) {
            totalCheckBlocks += segment.checkBlockCount;
            checkTotalDataBlocks += segment.dataBlockCount;
        }
        assert (checkTotalDataBlocks == totalDataBlocks);
        this.totalCheckBlocks = totalCheckBlocks;

        if (crossCheckBlocks != 0) {
            byte[] seed = Metadata.getCrossSegmentSeed(hashes, hashThisLayerOnly);
            if (logMINOR)
                Logger.minor(this, "Cross-segment seed: " + HexUtil.bytesToHex(seed));
            Random xsRandom = new MersenneTwister(seed);
            // Cross segment redundancy: Allocate the blocks.
            crossSegments = new SplitFileInserterCrossSegmentStorage[segs];
            int segLen = segmentSize;
            for (int i = 0; i < crossSegments.length; i++) {
                if (logMINOR)
                    Logger.minor(this, "Allocating blocks for cross segment " + i);
                if (segments.length - i == deductBlocksFromSegments) {
                    segLen--;
                }

                SplitFileInserterCrossSegmentStorage seg = new SplitFileInserterCrossSegmentStorage(
                        this, i, persistent, segLen, crossCheckBlocks);
                crossSegments[i] = seg;
                for (int j = 0; j < segLen; j++) {
                    // Allocate random data blocks
                    allocateCrossDataBlock(seg, xsRandom);
                }
                for (int j = 0; j < crossCheckBlocks; j++) {
                    // Allocate check blocks
                    allocateCrossCheckBlock(seg, xsRandom);
                }
            }
        } else {
            crossSegments = null;
        }

        // Now set up the RAF.

        // First we have all the fixed stuff ...

        byte[] paddedLastBlock = null;
        if (dataLength % CHKBlock.DATA_LENGTH != 0) {
            this.hasPaddedLastBlock = true;
            long from = (dataLength / CHKBlock.DATA_LENGTH) * CHKBlock.DATA_LENGTH;
            byte[] buf = new byte[(int) (dataLength - from)];
            this.originalData.pread(from, buf, 0, buf.length);
            paddedLastBlock = BucketTools.pad(buf, CHKBlock.DATA_LENGTH, buf.length);
        } else {
            this.hasPaddedLastBlock = false;
        }

        byte[] header = null;
        Bucket segmentSettings = null, crossSegmentSettings = null;
        if (persistent) {
            header = encodeHeader();

            segmentSettings = encodeSegmentSettings(); // Checksummed with length
            try {
                crossSegmentSettings = encodeCrossSegmentSettings(bf); // Individually checksummed with length
            } catch (IOException e) {
                throw new InsertException(InsertException.BUCKET_ERROR,
                        "Failed to write to temporary storage while creating splitfile inserter",
                        null);
            }
        }

        long ptr = 0;
        if (persistent) {
            ptr = header.length + segmentSettings.size() + crossSegmentSettings.size();
            offsetOverallStatus = ptr;
            overallStatusLength = encodeOverallStatus().length;
            ptr += overallStatusLength;

            // Pad to a 4KB block boundary.
            int padding = 0;
            padding = (int) (ptr % 4096);
            if (padding != 0)
                padding = 4096 - padding;

            ptr += padding;
        } else {
            overallStatusLength = 0;
            offsetOverallStatus = 0;
        }

        this.offsetPaddedLastBlock = ptr;

        if (hasPaddedLastBlock)
            ptr += CHKBlock.DATA_LENGTH;

        if (crossSegments != null) {
            offsetCrossSegmentBlocks = new long[crossSegments.length];
            for (int i = 0; i < crossSegments.length; i++) {
                offsetCrossSegmentBlocks[i] = ptr;
                ptr += crossSegments[i].crossCheckBlockCount * CHKBlock.DATA_LENGTH;
            }
        } else {
            offsetCrossSegmentBlocks = null;
        }

        offsetSegmentCheckBlocks = new long[segments.length];
        for (int i = 0; i < segments.length; i++) {
            offsetSegmentCheckBlocks[i] = ptr;
            ptr += segments[i].checkBlockCount * CHKBlock.DATA_LENGTH;
        }

        if (persistent) {
            offsetSegmentStatus = new long[segments.length];
            for (int i = 0; i < segments.length; i++) {
                offsetSegmentStatus[i] = ptr;
                ptr += segments[i].storedStatusLength();
            }

            if (crossSegments != null) {
                offsetCrossSegmentStatus = new long[crossSegments.length];
                for (int i = 0; i < crossSegments.length; i++) {
                    offsetCrossSegmentStatus[i] = ptr;
                    ptr += crossSegments[i].storedStatusLength();
                }
            } else {
                offsetCrossSegmentStatus = null;
            }

        } else {
            offsetSegmentStatus = null;
            offsetCrossSegmentStatus = null;
        }
        
        offsetSegmentKeys = new long[segments.length];
        for (int i = 0; i < segments.length; i++) {
            offsetSegmentKeys[i] = ptr;
            ptr += segments[i].storedKeysLength();
        }

        this.raf = rafFactory.makeRAF(ptr);
        if (persistent) {
            raf.pwrite(0, header, 0, header.length);
            BucketTools.copyTo(segmentSettings, raf, header.length, Long.MAX_VALUE);
            BucketTools.copyTo(crossSegmentSettings, raf, header.length + segmentSettings.size(),
                    Long.MAX_VALUE);
            segmentSettings.free();
            crossSegmentSettings.free();
            writeOverallStatus(true);
        }
        if (hasPaddedLastBlock)
            raf.pwrite(offsetPaddedLastBlock, paddedLastBlock, 0, paddedLastBlock.length);
        if (persistent) {
            // Padding is initialized to random already.
            for (SplitFileInserterSegmentStorage segment : segments) {
                segment.storeStatus(true);
            }
            if (crossSegments != null) {
                for (SplitFileInserterCrossSegmentStorage segment : crossSegments) {
                    segment.storeStatus();
                }
            }
            for (SplitFileInserterSegmentStorage segment : segments) {
                segment.clearKeys();
            }
        }
        // Keys are empty, and invalid.
        status = Status.NOT_STARTED;
    }

    private void writeOverallStatus(boolean force) throws IOException {
        byte[] buf;
        synchronized(this) {
            if(!persistent) return;
            if(!force && !overallStatusDirty) return;
            buf = encodeOverallStatus();
            assert(buf.length == overallStatusLength);
        }
        raf.pwrite(offsetOverallStatus, buf, 0, buf.length);
    }

    private byte[] encodeOverallStatus() {
        ArrayBucket bucket = new ArrayBucket(); // Will be small.
        try {
            OutputStream os = bucket.getOutputStream();
            OutputStream cos = checker.checksumWriterWithLength(os, new ArrayBucketFactory());
            DataOutputStream dos = new DataOutputStream(cos);
            synchronized(this) {
                errors.writeFixedLengthTo(dos);
                overallStatusDirty = false;
            }
            dos.close();
            os.close();
            return bucket.toByteArray();
        } catch (IOException e) {
            throw new Error(e); // Impossible
        }
    }

    private Bucket encodeSegmentSettings() {
        ArrayBucket bucket = new ArrayBucket(); // Will be small.
        try {
            OutputStream os = bucket.getOutputStream();
            OutputStream cos = checker.checksumWriterWithLength(os, new ArrayBucketFactory());
            DataOutputStream dos = new DataOutputStream(cos);
            for (SplitFileInserterSegmentStorage segment : segments) {
                segment.writeFixedSettings(dos);
            }
            dos.close();
            os.close();
            return bucket;
        } catch (IOException e) {
            throw new Error(e); // Impossible
        }
    }

    /**
     * This one could actually be rather large, since it includes the listing of
     * which blocks go in which cross-segments ...
     */
    private Bucket encodeCrossSegmentSettings(BucketFactory bf) throws IOException {
        if (crossSegments == null)
            return new NullBucket();
        Bucket bucket = bf.makeBucket(-1);
        OutputStream os = bucket.getOutputStream();
        OutputStream cos = checker.checksumWriterWithLength(os, new ArrayBucketFactory());
        DataOutputStream dos = new DataOutputStream(cos);
        for (SplitFileInserterCrossSegmentStorage segment : crossSegments) {
            segment.writeFixedSettings(dos);
        }
        dos.close();
        os.close();
        return null;
    }

    /** Includes magic, version, length, basic settings, checksum. */
    private byte[] encodeHeader() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeLong(MAGIC);
            OutputStream os = checker.checksumWriterWithLength(baos, new ArrayBucketFactory());
            dos = new DataOutputStream(os);
            dos.writeInt(VERSION);
            originalData.storeTo(dos);
            dos.writeInt(totalDataBlocks);
            dos.writeInt(totalCheckBlocks);
            dos.writeShort(splitfileType); // And hence the FECCodec
            dos.writeLong(dataLength);
            dos.writeLong(decompressedLength);
            clientMetadata.writeTo(dos);
            if (compressionCodec == null)
                dos.writeShort((short) -1);
            else
                dos.writeShort(compressionCodec.metadataID);
            dos.writeInt(segments.length);
            dos.writeInt(segmentSize);
            dos.writeInt(checkSegmentSize);
            dos.writeInt(crossCheckBlocks);
            dos.writeByte(this.splitfileCryptoAlgorithm);
            dos.writeBoolean(this.splitfileCryptoKey != null);
            if (this.splitfileCryptoKey != null) {
                assert (splitfileCryptoKey.length == 32);
                dos.write(splitfileCryptoKey);
            }
            dos.writeInt(keyLength);
            dos.writeInt(cmode.ordinal());
            // hasPaddedLastBlock will be recomputed.
            dos.writeInt(deductBlocksFromSegments);
            dos.writeInt(maxRetries);
            dos.writeInt(consecutiveRNFsCountAsSuccess);
            // FIXME do we want to include offsets???
            dos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new Error(e); // Impossible
        }
    }

    private void allocateCrossDataBlock(SplitFileInserterCrossSegmentStorage segment, Random xsRandom) {
        int x = 0;
        for (int i = 0; i < 10; i++) {
            x = xsRandom.nextInt(segments.length);
            SplitFileInserterSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossDataBlock(segment, xsRandom);
            if (blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        for (int i = 0; i < segments.length; i++) {
            x++;
            if (x == segments.length)
                x = 0;
            SplitFileInserterSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossDataBlock(segment, xsRandom);
            if (blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        throw new IllegalStateException("Unable to allocate cross data block!");
    }

    private void allocateCrossCheckBlock(SplitFileInserterCrossSegmentStorage segment, Random xsRandom) {
        int x = 0;
        for (int i = 0; i < 10; i++) {
            x = xsRandom.nextInt(segments.length);
            SplitFileInserterSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossCheckBlock(segment, xsRandom,
                    segment.getAllocatedCrossCheckBlocks());
            if (blockNum >= 0) {
                segment.addCheckBlock(seg, blockNum);
                return;
            }
        }
        for (int i = 0; i < segments.length; i++) {
            x++;
            if (x == segments.length)
                x = 0;
            SplitFileInserterSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossCheckBlock(segment, xsRandom,
                    segment.getAllocatedCrossCheckBlocks());
            if (blockNum >= 0) {
                segment.addCheckBlock(seg, blockNum);
                return;
            }
        }
        throw new IllegalStateException("Unable to allocate cross data block!");
    }

    private SplitFileInserterSegmentStorage[] makeSegments(int segmentSize, int segCount,
            int dataBlocks, int crossCheckBlocks, int deductBlocksFromSegments, boolean persistent,
            byte cryptoAlgorithm, byte[] cryptoKey, CompatibilityMode cmode, Random random, 
            KeysFetchingLocally keysFetching, int consecutiveRNFsCountAsSuccess) {
        SplitFileInserterSegmentStorage[] segments = new SplitFileInserterSegmentStorage[segCount];
        if (segCount == 1) {
            // Single segment
            int checkBlocks = codec.getCheckBlocks(dataBlocks + crossCheckBlocks, cmode);
            segments[0] = new SplitFileInserterSegmentStorage(this, 0, persistent, dataBlocks,
                    checkBlocks, crossCheckBlocks, keyLength, cryptoAlgorithm, cryptoKey, random, 
                    maxRetries, consecutiveRNFsCountAsSuccess, keysFetching);
        } else {
            int j = 0;
            int segNo = 0;
            int data = segmentSize;
            int check = codec.getCheckBlocks(data + crossCheckBlocks, cmode);
            for (int i = segmentSize;;) {
                this.underlyingOffsetDataSegments[segNo] = j * CHKBlock.DATA_LENGTH;
                if (i > dataBlocks)
                    i = dataBlocks;
                if (data > (i - j)) {
                    // Last segment.
                    assert (segNo == segCount - 1);
                    data = i - j;
                    check = codec.getCheckBlocks(data + crossCheckBlocks, cmode);
                }
                j = i;
                segments[segNo] = new SplitFileInserterSegmentStorage(this, segNo, persistent,
                        data, check, crossCheckBlocks, keyLength, cryptoAlgorithm, cryptoKey, 
                        random, maxRetries, consecutiveRNFsCountAsSuccess, keysFetching);

                if (deductBlocksFromSegments != 0)
                    if (logMINOR)
                        Logger.minor(this, "INSERTING: Segment " + segNo + " of " + segCount
                                + " : " + data + " data blocks " + check + " check blocks");

                segNo++;
                if (i == dataBlocks)
                    break;
                // Deduct one block from each later segment, rather than having
                // a really short last segment.
                if (segCount - segNo == deductBlocksFromSegments) {
                    data--;
                    // Don't change check.
                }
                i += data;
            }
            assert (segNo == segCount);
        }
        return segments;
    }

    public void start() {
        synchronized (this) {
            if (status != Status.NOT_STARTED)
                return;
            status = Status.STARTED;
        }
        if (crossSegments != null) {
            // Cross-segment encode must complete before main encode.
            startCrossSegmentEncode();
        } else {
            startSegmentEncode();
        }
    }

    private void startSegmentEncode() {
        for (SplitFileInserterSegmentStorage segment : segments)
            segment.startEncode();
    }

    private void startCrossSegmentEncode() {
        // Start cross-segment encode.
        for (SplitFileInserterCrossSegmentStorage segment : crossSegments)
            segment.startEncode();
    }

    /** Called when a cross-segment finishes encoding blocks. Can be called inside locks as it runs
     * off-thread.
     * @param completed
     */
    public void onFinishedEncoding(SplitFileInserterCrossSegmentStorage completed) {
        jobRunner.queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                callback.encodingProgress();
                if(maybeFail()) return true;
                if(allFinishedCrossEncoding()) {
                    onCompletedCrossSegmentEncode();
                }
                return false;
            }
            
        });
    }

    private boolean allFinishedCrossEncoding() {
        for (SplitFileInserterCrossSegmentStorage segment : crossSegments) {
            if (!segment.isFinishedEncoding())
                return false;
        }
        return true;
    }

    /** Called when a segment finishes encoding blocks. Can be called inside locks as it runs
     * off-thread.
     * @param completed
     */
    public void onFinishedEncoding(final SplitFileInserterSegmentStorage completed) {
        jobRunner.queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                completed.storeStatus(true);
                callback.encodingProgress();
                if(maybeFail()) return true;
                if(allFinishedEncoding()) {
                    onCompletedSegmentEncode();
                }
                return false;
            }
            
        });
    }

    private boolean allFinishedEncoding() {
        for (SplitFileInserterSegmentStorage segment : segments) {
            if (!segment.isFinishedEncoding())
                return false;
        }
        return true;
    }

    /** Called when we have completed encoding all the cross-segments */
    private void onCompletedCrossSegmentEncode() {
        synchronized (this) {
            if (status != Status.STARTED) {
                Logger.error(this, "Wrong state " + status, new Exception("error"));
                return;
            }
            status = Status.ENCODED_CROSS_SEGMENTS;
        }
        startSegmentEncode();
    }

    private void onCompletedSegmentEncode() {
        synchronized (this) {
            if (!(status == Status.ENCODED_CROSS_SEGMENTS || (crossSegments == null && status == Status.STARTED))) {
                Logger.error(this, "Wrong state " + status, new Exception("error"));
                return;
            }
            status = Status.ENCODED;
        }
        callback.onFinishedEncode();
    }
    
    public void onHasKeys(SplitFileInserterSegmentStorage splitFileInserterSegmentStorage) {
        for (SplitFileInserterSegmentStorage segment : segments) {
            if (!segment.hasKeys())
                return;
        }
        onHasKeys();
    }

    /** Called when we have keys for every block. */
    private void onHasKeys() {
        callback.onHasKeys();
    }
    
    /**
     * Create an OutputStream that we can write formatted data to of a specific
     * length. On close(), it checks that the length is as expected, computes
     * the checksum, and writes the data to the specified position in the file.
     * 
     * @param fileOffset
     *            The position in the file (raf) of the first byte.
     * @param length
     *            The length, including checksum, of the data to be written.
     * @return
     */
    OutputStream writeChecksummedTo(final long fileOffset, final int length) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(length);
        OutputStream cos = checker.checksumWriter(baos);
        return new FilterOutputStream(cos) {

            public void close() throws IOException {
                out.close();
                byte[] buf = baos.toByteArray();
                if (buf.length != length)
                    throw new IllegalStateException("Wrote wrong number of bytes: " + buf.length
                            + " should be " + length);
                raf.pwrite(fileOffset, buf, 0, length);
            }

        };
    }

    long segmentStatusOffset(int segNo) {
        return offsetSegmentStatus[segNo];
    }

    long crossSegmentStatusOffset(int segNo) {
        return offsetCrossSegmentStatus[segNo];
    }

    static final long MAGIC = 0x4d2a3f596bbf5de5L;
    static final int VERSION = 1;

    public boolean hasSplitfileKey() {
        return splitfileCryptoKey != null;
    }

    /**
     * Write a cross-check block to disk
     * 
     * @throws IOException
     */
    void writeCheckBlock(int segNo, int checkBlockNo, byte[] buf) throws IOException {
        synchronized (this) {
            if (status == Status.ENCODED || status == Status.ENCODED_CROSS_SEGMENTS)
                throw new IllegalStateException("Already encoded!?");
        }
        assert (segNo >= 0 && segNo < crossSegments.length);
        assert (checkBlockNo >= 0 && checkBlockNo < crossCheckBlocks);
        assert (buf.length == CHKBlock.DATA_LENGTH);
        long offset = offsetCrossSegmentBlocks[segNo] + checkBlockNo * CHKBlock.DATA_LENGTH;
        raf.pwrite(offset, buf, 0, buf.length);
    }

    public byte[] readCheckBlock(int segNo, int checkBlockNo) throws IOException {
        assert (segNo >= 0 && segNo < crossSegments.length);
        assert (checkBlockNo >= 0 && checkBlockNo < crossCheckBlocks);
        long offset = offsetCrossSegmentBlocks[segNo] + checkBlockNo * CHKBlock.DATA_LENGTH;
        byte[] buf = new byte[CHKBlock.DATA_LENGTH];
        raf.pread(offset, buf, 0, buf.length);
        return buf;
    }

    /**
     * Lock the main RAF open to avoid the pooled fd being closed when we are
     * doing a major I/O operation involving many reads/writes.
     */
    RAFLock lockRAF() throws IOException {
        return raf.lockOpen();
    }

    /**
     * Lock the originalData RAF open to avoid the pooled fd being closed when
     * we are doing a major I/O operation involving many reads/writes.
     * 
     * @throws IOException
     */
    RAFLock lockUnderlying() throws IOException {
        return originalData.lockOpen();
    }

    public byte[] readSegmentDataBlock(int segNo, int blockNo) throws IOException {
        assert (segNo >= 0 && segNo < segments.length);
        assert (blockNo >= 0 && blockNo < segments[segNo].dataBlockCount);
        byte[] buf = new byte[CHKBlock.DATA_LENGTH];
        if (hasPaddedLastBlock) {
            if (segNo == segments.length - 1 && blockNo == segments[segNo].dataBlockCount - 1) {
                // Don't need to lock, locking is just an optimisation.
                raf.pread(offsetPaddedLastBlock, buf, 0, buf.length);
                return buf;
            }
        }
        long offset = underlyingOffsetDataSegments[segNo] + blockNo * CHKBlock.DATA_LENGTH;
        originalData.pread(offset, buf, 0, buf.length);
        return buf;
    }

    public void writeSegmentCheckBlock(int segNo, int checkBlockNo, byte[] buf) throws IOException {
        assert (segNo >= 0 && segNo < segments.length);
        assert (checkBlockNo >= 0 && checkBlockNo < segments[segNo].checkBlockCount);
        assert (buf.length == CHKBlock.DATA_LENGTH);
        long offset = offsetSegmentCheckBlocks[segNo] + checkBlockNo * CHKBlock.DATA_LENGTH;
        raf.pwrite(offset, buf, 0, buf.length);
    }
    
    public byte[] readSegmentCheckBlock(int segNo, int checkBlockNo) throws IOException {
        assert (segNo >= 0 && segNo < segments.length);
        assert (checkBlockNo >= 0 && checkBlockNo < segments[segNo].checkBlockCount);
        byte[] buf = new byte[CHKBlock.DATA_LENGTH];
        long offset = offsetSegmentCheckBlocks[segNo] + checkBlockNo * CHKBlock.DATA_LENGTH;
        raf.pread(offset, buf, 0, buf.length);
        return buf;
    }
    
    /** Encode the Metadata. The caller must ensure that all segments have encoded keys first.
     * @throws MissingKeyException This indicates disk corruption or a bug (e.g. not all segments
     * had encoded keys). Since we don't checksum the blocks, there isn't much point in trying to
     * recover from losing a key; but at least we can detect that there was a problem.
     * 
     * (Package-visible for unit tests)
     */
    Metadata encodeMetadata() throws IOException, MissingKeyException {
        ClientCHK[] dataKeys = new ClientCHK[totalDataBlocks + crossCheckBlocks * segments.length];
        ClientCHK[] checkKeys = new ClientCHK[totalCheckBlocks];
        int dataPtr = 0;
        int checkPtr = 0;
        for(int segNo = 0; segNo < segments.length; segNo++) {
            SplitFileInserterSegmentStorage segment = segments[segNo];
            for(int i=0;i<segment.dataBlockCount+segment.crossCheckBlockCount;i++) {
                dataKeys[dataPtr++] = segment.readKey(i);
            }
            for(int i=0;i<segment.checkBlockCount;i++) {
                checkKeys[checkPtr++] = segment.readKey(i+segment.dataBlockCount+segment.crossCheckBlockCount);
            }
        }
        assert(dataPtr == dataKeys.length);
        assert(checkPtr == checkKeys.length);
        return new Metadata(splitfileType, dataKeys, checkKeys, segmentSize, checkSegmentSize, 
                deductBlocksFromSegments, clientMetadata, dataLength, archiveType, compressionCodec, 
                decompressedLength, isMetadata, hashes, hashThisLayerOnly, origDataSize, 
                origCompressedDataSize, topRequiredBlocks, topTotalBlocks, topDontCompress, 
                (short)cmode.ordinal(), splitfileCryptoAlgorithm, splitfileCryptoKey, 
                specifySplitfileKeyInMetadata, crossCheckBlocks);
    }

    void innerWriteSegmentKey(int segNo, int blockNo, byte[] buf) throws IOException {
        assert (buf.length == SplitFileInserterSegmentStorage.getKeyLength(this));
        assert (segNo >= 0 && segNo < segments.length);
        assert (blockNo >= 0 && blockNo < segments[segNo].totalBlockCount);
        raf.pwrite(this.offsetSegmentKeys[segNo] + keyLength * blockNo, buf, 0, buf.length);
    }
    
    byte[] innerReadSegmentKey(int segNo, int blockNo) throws IOException {
        byte[] buf = new byte[keyLength];
        raf.pread(this.offsetSegmentKeys[segNo] + keyLength * blockNo, buf, 0, buf.length);
        return buf;
    }

    public int totalCrossCheckBlocks() {
        return segments.length * crossCheckBlocks;
    }

    /** Called when a segment completes. Can be called inside locks as it runs off-thread. */
    public void segmentSucceeded(SplitFileInserterSegmentStorage completedSegment) {
        jobRunner.queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                if(maybeFail()) return true;
                if(allSegmentsSucceeded()) {
                    synchronized(this) {
                        assert(failing == null);
                        if(hasFinished()) return false;
                        status = Status.GENERATING_METADATA;
                    }
                    try {
                        Metadata metadata = encodeMetadata();
                        callback.onSucceeded(metadata);
                        synchronized(this) {
                            status = Status.SUCCEEDED;
                        }
                    } catch (IOException e) {
                        InsertException e1 = new InsertException(InsertException.BUCKET_ERROR);
                        synchronized(this) {
                            failing = e1;
                            status = Status.FAILED;
                        }
                        callback.onFailed(e1);
                    } catch (MissingKeyException e) {
                        // Fail here too. If we're getting disk corruption on keys, we're probably 
                        // getting it on the original data too.
                        InsertException e1 = new InsertException(InsertException.BUCKET_ERROR, "Missing keys", null);
                        synchronized(this) {
                            failing = e1;
                            status = Status.FAILED;
                        }
                        callback.onFailed(e1);
                    }
                }
                return true;
            }
            
        });
    }

    private boolean maybeFail() {
        // Might have failed.
        // Have to check segments before checking for failure because of race conditions.
        if(allSegmentsCompletedOrFailed()) {
            InsertException e = null;
            synchronized(this) {
                if(failing == null) return false;
                e = failing;
                if(hasFinished()) return true;
                status = Status.FAILED;
            }
            callback.onFailed(e);
            return true;
        } else {
            return false;
        }
    }

    private boolean allSegmentsCompletedOrFailed() {
        for(SplitFileInserterSegmentStorage segment : segments) {
            if(!segment.hasCompletedOrFailed()) return false;
        }
        if(crossSegments != null) {
            for(SplitFileInserterCrossSegmentStorage segment : crossSegments) {
                if(!segment.hasCompletedOrFailed()) return false;
            }
        }
        return true;
    }

    private boolean allSegmentsSucceeded() {
        for(SplitFileInserterSegmentStorage segment : segments) {
            if(!segment.hasSucceeded()) return false;
        }
        return true;
    }

    public void addFailure(InsertException e) {
        errors.inc(e.getMode());
        synchronized(this) {
            overallStatusDirty = true;
            lazyWriteMetadata();
        }
    }

    public void failOnDiskError(IOException e) {
        fail(new InsertException(InsertException.BUCKET_ERROR));
    }
    
    public void failFatalErrorInBlock() {
        fail(new InsertException(InsertException.FATAL_ERRORS_IN_BLOCKS, errors, null));
    }
    
    public void failTooManyRetriesInBlock() {
        fail(new InsertException(InsertException.TOO_MANY_RETRIES_IN_BLOCKS, errors, null));
    }
    
    private void fail(final InsertException e) {
        synchronized(this) {
            // Only fail once.
            if(failing != null) return;
            failing = e;
        }
        jobRunner.queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                // Tell the segments to cancel.
                boolean allDone = true;
                for(SplitFileInserterSegmentStorage segment : segments) {
                    if(!segment.cancel()) allDone = false;
                }
                if(crossSegments != null) {
                    for(SplitFileInserterCrossSegmentStorage segment : crossSegments) {
                        if(!segment.cancel()) allDone = false;
                    }
                }
                if(allDone) {
                    synchronized(this) {
                        // Could have beaten us to it in callback.
                        if(hasFinished()) return false;
                        status = Status.FAILED;
                    }
                    callback.onFailed(e);
                    return true;
                } else {
                    // Wait for them to finish encoding.
                    return false;
                }
            }
            
        });
    }

    public synchronized boolean hasFinished() {
        return status == Status.SUCCEEDED || status == Status.FAILED;
    }

    public synchronized Status getStatus() {
        return status;
    }

    /** Used by KeysFetchingLocally */
    SendableInsert getSendableInsert() {
        return callback.getSendableInsert();
    }
    
    static final long LAZY_WRITE_METADATA_DELAY = TimeUnit.MINUTES.toMillis(5);
    
    private final PersistentJob writeMetadataJob = new PersistentJob() {

        @Override
        public boolean run(ClientContext context) {
            try {
                if(isFinishing()) return false;
                RAFLock lock = raf.lockOpen();
                try {
                    for(SplitFileInserterSegmentStorage segment : segments) {
                        segment.storeStatus(false);
                    }
                } finally {
                    lock.unlock();
                }
                writeOverallStatus(false);
                return false;
            } catch (IOException e) {
                if(isFinishing()) return false;
                Logger.error(this, "Failed writing metadata for "+SplitFileInserterStorage.this+": "+e, e);
                return false;
            }
        }
        
    };
    
    private final Runnable wrapLazyWriteMetadata = new Runnable() {

        @Override
        public void run() {
            jobRunner.queueNormalOrDrop(writeMetadataJob);
        }
        
    };

    public synchronized void lazyWriteMetadata() {
        if(!persistent) return;
        if(LAZY_WRITE_METADATA_DELAY != 0) {
            // The Runnable must be the same object for de-duplication.
            ticker.queueTimedJob(wrapLazyWriteMetadata, "Write metadata for splitfile", 
                    LAZY_WRITE_METADATA_DELAY, false, true);
        } else { // Must still be off-thread, multiple segments, possible locking issues...
            jobRunner.queueNormalOrDrop(writeMetadataJob);
        }
    }

    protected synchronized boolean isFinishing() {
        return this.failing != null || status == Status.FAILED || status == Status.SUCCEEDED || 
            status == Status.GENERATING_METADATA;
    }
    
    void onShutdown(ClientContext context) {
        writeMetadataJob.run(context);
    }

}
