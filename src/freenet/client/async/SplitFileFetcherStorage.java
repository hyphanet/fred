package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.FailureCodeTracker;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.MetadataUnresolvedException;
import freenet.client.NewFECCodec;
import freenet.crypt.ChecksumOutputStream;
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestItemKey;
import freenet.support.Executor;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.MemoryLimitedJobRunner;
import freenet.support.Ticker;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.io.LockableRandomAccessThing;
import freenet.support.io.LockableRandomAccessThing.RAFLock;
import freenet.support.io.LockableRandomAccessThingFactory;

/** <p>Stores the state for a SplitFileFetcher, persisted to a LockableRandomAccessThing (i.e. a 
 * single random access file), but with most of the metadata in memory. The data, and the larger
 * metadata such as the full keys, are read from disk when needed, and persisted to disk.</p>
 * 
 * <p>On disk format goals:</p>
 * <ol><li>Maximise robustness.</li>
 * <li>Minimise seeks.</li>
 * <li>Minimise disk usage.</li>
 * <li>Be as simple as realistically possible.</li></ol>
 * 
 * <p>Overall on-disk structure:
 * BLOCK STORAGE: Decoded data, one segment at a time (the last segment's size is rounded up to a 
 * whole block). Within each segment, the number of blocks is equal to the number of data blocks 
 * (plus the number of cross-check blocks if there are cross-check blocks), but they are not 
 * necessarily actually data blocks (they may be check blocks), and they may not be in the correct 
 * order. When we FEC decode, we read in the blocks, construct the CHKs to see what keys they 
 * belong to, check that we still have enough valid keys (update the metadata if the counts were 
 * wrong), do the decode, and write the data blocks back in the correct order; the segment is 
 * finished. When all the segments are finished, we generate a stream as usual, i.e. we still need
 * to copy the file. It may be possible in future to simply truncate the file but in many cases we
 * need to decompress or filter, and there are significant issues with code complexity and seeks 
 * during FEC decodes, see bug #6063. 
 * 
 * KEY LIST: The original key list. Not changed when a block is fetched.
 * - Fixed and checksummed (each segment has a checksum).
 * 
 * SEGMENT STATUS: The status of each segment, including the status of each block, including flags 
 * and where it is in the block storage within the segment.
 * - Checksummed per segment. So it needs to be written as a whole segment. Can be regenerated from 
 * the block store and key list, which happens routinely when FEC decoding.
 * 
 * BLOOM FILTERS: Main bloom filter. Segment bloom filters.
 * 
 * ORIGINAL METADATA: For extra robustness, keep the full original metadata.
 * 
 * ORIGINAL URL: If the original key is available, keep that too.
 * 
 * BASIC SETTINGS: Type of splitfile, length of file, overall decryption key, number of blocks and 
 * check blocks per segment, etc.
 * - Fixed and checksummed. Read as a block so we can check the checksum.
 * 
 * FOOTER:
 * Length of basic settings. (So we can seek back to get them)
 * Version number.
 * Magic value.
 * 
 * 
 * OTHER NOTES:
 * 
 * CONCURRENCY: Callbacks into fetcher should be run off-thread, as they will usually be inside a 
 * MemoryLimitedJob.
 * @author toad
 */
public class SplitFileFetcherStorage {

    private boolean logMINOR;
    static {
        Logger.registerClass(SplitFileFetcherStorage.class);
    }
    
    final SplitFileFetcherCallback fetcher;
    // Metadata for the fetch
    /** The underlying presumably-on-disk storage. */ 
    final LockableRandomAccessThing raf;
    /** The segments */
    final SplitFileFetcherSegmentStorage[] segments;
    /** The cross-segments. Null if no cross-segments. */
    private final SplitFileFetcherCrossSegmentStorage[] crossSegments;
    /** If the splitfile has a common encryption algorithm, this is it. */
    final byte splitfileSingleCryptoAlgorithm;
    /** If the splitfile has a common encryption key, this is it. */
    final byte[] splitfileSingleCryptoKey;
    /** FEC codec for the splitfile, if needed. */
    public final NewFECCodec fecCodec;
    final Ticker ticker;
    final Executor executor;
    final MemoryLimitedJobRunner memoryLimitedJobRunner;
    final long finalLength;
    final short splitfileType;
    /** MIME type etc. Set on construction and passed to onSuccess(). */
    final ClientMetadata clientMetadata;
    /** Decompressors. Set on construction and passed to onSuccess(). */
    final List<COMPRESSOR_TYPE> decompressors;
    
    private boolean finishedFetcher;
    private boolean finishedEncoding;
    private boolean cancelled;
    
    /** Errors. For now, this is not persisted (FIXME). */
    private final FailureCodeTracker errors;
    private final int maxRetries;
    
    /** Contains Bloom filters */
    final SplitFileFetcherKeyListenerNew keyListener;
    
    final RandomSource random;
    
    // Metadata for the file i.e. stuff we need to be able to efficiently read/write it.
    /** Offset to start of the key lists in bytes */
    final long offsetKeyList;
    /** Offset to start of the segment status'es in bytes */
    final long offsetSegmentStatus;
    /** Offset to start of the bloom filters in bytes */
    final long offsetMainBloomFilter;
    /** Offset to start of the per-segment bloom filters in bytes */
    final long offsetSegmentBloomFilters;
    /** Offset to start of the original metadata in bytes */
    final long offsetOriginalMetadata;
    /** Offset to start of the original URL in bytes */
    final long offsetOriginalURI;
    /** Offset to start of the basic settings in bytes */
    final long offsetBasicSettings;
    /** Fixed value posted at the end of the file (if plaintext!) */
    static final long END_MAGIC = 0x28b32d99416eb6efL;
    /** Current format version */
    static final int VERSION = 1;
    
    /** Construct a new SplitFileFetcherStorage from metadata. Creates the RandomAccessThing and
     * writes the initial data to it. There is another constructor for resuming a download. 
     * @param persistent 
     * @param topCompatibilityMode 
     * @param clientMetadata2 
     * @param decompressors2
     * @param cb This is only provided so we can create appropriate events when constructing, we do
     * not store it. 
     * @throws FetchException If we failed to set up the download due to a problem with the metadata. 
     * @throws MetadataParseException 
     * @throws IOException If we were unable to create the file to store the metadata and 
     * downloaded blocks in. */
    public SplitFileFetcherStorage(Metadata metadata, SplitFileFetcherCallback fetcher, 
            List<COMPRESSOR_TYPE> decompressors, ClientMetadata clientMetadata, 
            boolean topDontCompress, short topCompatibilityMode, FetchContext origFetchContext,
            boolean realTime, KeySalter salt, FreenetURI thisKey, RandomSource random, 
            BucketFactory tempBucketFactory, LockableRandomAccessThingFactory rafFactory, Executor exec,
            Ticker ticker, MemoryLimitedJobRunner memoryLimitedJobRunner) 
    throws FetchException, MetadataParseException, IOException {
        this.fetcher = fetcher;
        this.executor = exec;
        this.ticker = ticker;
        this.memoryLimitedJobRunner = memoryLimitedJobRunner;
        this.finalLength = metadata.dataLength();
        this.splitfileType = metadata.getSplitfileType();
        this.fecCodec = NewFECCodec.getInstance(splitfileType);
        this.decompressors = decompressors;
        this.random = random;
        this.errors = new FailureCodeTracker(false);
        if(decompressors.size() > 1) {
            Logger.error(this, "Multiple decompressors: "+decompressors.size()+" - this is almost certainly a bug", new Exception("debug"));
        }
        this.clientMetadata = clientMetadata == null ? new ClientMetadata() : clientMetadata.clone(); // copy it as in SingleFileFetcher
        SplitFileSegmentKeys[] segmentKeys = metadata.getSegmentKeys();
        CompatibilityMode minCompatMode = metadata.getMinCompatMode();
        CompatibilityMode maxCompatMode = metadata.getMaxCompatMode();

        int crossCheckBlocks = metadata.getCrossCheckBlocks();
        
        maxRetries = origFetchContext.maxSplitfileBlockRetries;
        this.splitfileSingleCryptoAlgorithm = metadata.getSplitfileCryptoAlgorithm();
        splitfileSingleCryptoKey = metadata.getSplitfileCryptoKey();
        
        // These are approximate values, the number of blocks per segment varies.
        int blocksPerSegment = metadata.getDataBlocksPerSegment();
        int checkBlocksPerSegment = metadata.getCheckBlocksPerSegment();
        
        int splitfileDataBlocks = 0;
        int splitfileCheckBlocks = 0;
        
        long storedBlocksLength = 0;
        long storedKeysLength = 0;
        long storedSegmentStatusLength = 0;

        for(SplitFileSegmentKeys keys : segmentKeys) {
            int dataBlocks = keys.getDataBlocks();
            int checkBlocks = keys.getCheckBlocks();
            splitfileDataBlocks += dataBlocks;
            splitfileCheckBlocks += checkBlocks;
            storedKeysLength +=
                SplitFileFetcherSegmentStorage.storedKeysLength(dataBlocks, checkBlocks, splitfileSingleCryptoKey != null);
            storedSegmentStatusLength +=
                SplitFileFetcherSegmentStorage.paddedStoredSegmentStatusLength(dataBlocks, checkBlocks, 
                        crossCheckBlocks, maxRetries != -1);
        }
        
        storedBlocksLength = (splitfileDataBlocks + splitfileCheckBlocks) * CHKBlock.DATA_LENGTH;
        
        int segmentCount = metadata.getSegmentCount();
        
        if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT) {
            if(splitfileCheckBlocks > 0) {
                Logger.error(this, "Splitfile type is SPLITFILE_NONREDUNDANT yet "+splitfileCheckBlocks+" check blocks found!! : "+this);
                throw new FetchException(FetchException.INVALID_METADATA, "Splitfile type is non-redundant yet have "+splitfileCheckBlocks+" check blocks");
            }
        } else if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
            
            boolean dontCompress = decompressors.isEmpty();
            if(topCompatibilityMode != 0) {
                // If we have top compatibility mode, then we can give a definitive answer immediately, with the splitfile key, with dontcompress, etc etc.
                if(minCompatMode == CompatibilityMode.COMPAT_UNKNOWN ||
                        !(minCompatMode.ordinal() > topCompatibilityMode || maxCompatMode.ordinal() < topCompatibilityMode)) {
                    minCompatMode = maxCompatMode = CompatibilityMode.values()[topCompatibilityMode];
                    dontCompress = topDontCompress;
                } else
                    throw new FetchException(FetchException.INVALID_METADATA, "Top compatibility mode is incompatible with detected compatibility mode");
            }
            // We assume we are the bottom layer. 
            // If the top-block stats are passed in then we can safely say the report is definitive.
            fetcher.onSplitfileCompatibilityMode(minCompatMode, maxCompatMode, metadata.getCustomSplitfileKey(), dontCompress, true, topCompatibilityMode != 0);

            if((blocksPerSegment > origFetchContext.maxDataBlocksPerSegment)
                    || (checkBlocksPerSegment > origFetchContext.maxCheckBlocksPerSegment))
                throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
            
                
        } else throw new MetadataParseException("Unknown splitfile format: "+splitfileType);

        long maxTempLength = origFetchContext.maxTempLength;
        // FIXME check maxTempLength against final storage file size.
        if(logMINOR)
            Logger.minor(this, "Algorithm: "+splitfileType+", blocks per segment: "+blocksPerSegment+
                    ", check blocks per segment: "+checkBlocksPerSegment+", segments: "+segmentCount+
                    ", data blocks: "+splitfileDataBlocks+", check blocks: "+splitfileCheckBlocks);
        segments = new SplitFileFetcherSegmentStorage[segmentCount]; // initially null on all entries
        
        long checkLength = 1L * (splitfileDataBlocks - segmentCount * crossCheckBlocks) * CHKBlock.DATA_LENGTH;
        if(checkLength > finalLength) {
            if(checkLength - finalLength > CHKBlock.DATA_LENGTH)
                throw new FetchException(FetchException.INVALID_METADATA, "Splitfile is "+checkLength+" bytes long but length is "+finalLength+" bytes");
        }
        
        byte[] localSalt = new byte[32];
        random.nextBytes(localSalt);
        
        keyListener = new SplitFileFetcherKeyListenerNew(fetcher, this, realTime, false, 
                localSalt, splitfileDataBlocks + splitfileCheckBlocks, blocksPerSegment + 
                checkBlocksPerSegment, segmentCount);

        boolean pre1254 = !(minCompatMode == CompatibilityMode.COMPAT_CURRENT || minCompatMode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal());
        boolean pre1250 = (minCompatMode == CompatibilityMode.COMPAT_UNKNOWN || minCompatMode == CompatibilityMode.COMPAT_1250_EXACT);
        
        this.offsetKeyList = storedBlocksLength;
        this.offsetSegmentStatus = offsetKeyList + storedKeysLength;
        this.offsetMainBloomFilter = offsetSegmentStatus + storedSegmentStatusLength;
        this.offsetSegmentBloomFilters = offsetMainBloomFilter + keyListener.paddedMainBloomFilterSize();
        this.offsetOriginalMetadata = offsetSegmentBloomFilters + 
            keyListener.totalSegmentBloomFiltersSize();
            
        
        long dataOffset = 0;
        long segmentKeysOffset = offsetKeyList;
        long segmentStatusOffset = offsetSegmentStatus;
        
        for(int i=0;i<segments.length;i++) {
            // splitfile* will be overwritten, this is bad
            // so copy them
            SplitFileSegmentKeys keys = segmentKeys[i];
            int dataBlocks = keys.getDataBlocks();
            int checkBlocks = keys.getCheckBlocks();
            if((dataBlocks > origFetchContext.maxDataBlocksPerSegment)
                    || (checkBlocks > origFetchContext.maxCheckBlocksPerSegment))
                throw new FetchException(FetchException.TOO_MANY_BLOCKS_PER_SEGMENT, "Too many blocks per segment: "+blocksPerSegment+" data, "+checkBlocksPerSegment+" check");
            segments[i] = new SplitFileFetcherSegmentStorage(this, i, splitfileType, 
                    dataBlocks-crossCheckBlocks, // Cross check blocks are included in data blocks for SplitFileSegmentKeys' purposes.
                    checkBlocks, crossCheckBlocks, dataOffset, segmentKeysOffset, segmentStatusOffset,
                    splitfileSingleCryptoKey != null, keys);
            dataOffset += (dataBlocks+checkBlocks) * CHKBlock.DATA_LENGTH;
            segmentKeysOffset += 
                SplitFileFetcherSegmentStorage.storedKeysLength(dataBlocks, checkBlocks, splitfileSingleCryptoKey != null);
            segmentStatusOffset +=
                SplitFileFetcherSegmentStorage.paddedStoredSegmentStatusLength(dataBlocks, checkBlocks, 
                        crossCheckBlocks, maxRetries != -1);
            int data = keys.getDataBlocks();
            int check = keys.getCheckBlocks();
            for(int j=0;j<(data+check);j++) {
                keyListener.addKey(keys.getKey(j, null, false).getNodeKey(false), i, salt);
            }
        }
        assert(dataOffset == storedBlocksLength);
        assert(segmentKeysOffset == storedBlocksLength + storedKeysLength);
        assert(segmentStatusOffset == storedBlocksLength + storedKeysLength + storedSegmentStatusLength);
        int totalCrossCheckBlocks = segments.length * crossCheckBlocks;
        fetcher.setSplitfileBlocks(splitfileDataBlocks - totalCrossCheckBlocks, splitfileCheckBlocks + totalCrossCheckBlocks);
        
        keyListener.finishedSetup();
        
        int deductBlocksFromSegments = metadata.getDeductBlocksFromSegments();
        
        if(crossCheckBlocks != 0) {
            // FIXME
            throw new UnsupportedOperationException();
//            Random random = new MersenneTwister(Metadata.getCrossSegmentSeed(metadata.getHashes(), metadata.getHashThisLayerOnly()));
//            // Cross segment redundancy: Allocate the blocks.
//            crossSegments = new SplitFileFetcherCrossSegmentStorage[segments.length];
//            int segLen = blocksPerSegment;
//            for(int i=0;i<crossSegments.length;i++) {
//                Logger.normal(this, "Allocating blocks (on fetch) for cross segment "+i);
//                if(segments.length - i == deductBlocksFromSegments) {
//                    segLen--;
//                }
//                SplitFileFetcherCrossSegmentStorage seg = 
//                    new SplitFileFetcherCrossSegmentStorage(i, segLen, crossCheckBlocks, parent, 
//                            this, splitfileType);
//                crossSegments[i] = seg;
//                for(int j=0;j<segLen;j++) {
//                    // Allocate random data blocks
//                    allocateCrossDataBlock(seg, random);
//                }
//                for(int j=0;j<crossCheckBlocks;j++) {
//                    // Allocate check blocks
//                    allocateCrossCheckBlock(seg, random);
//                }
//            }
        } else {
            crossSegments = null;
        }
        
        // Write the metadata to a temporary file to get its exact length.
        Bucket metadataTemp = tempBucketFactory.makeBucket(-1);
        OutputStream os = metadataTemp.getOutputStream();
        ChecksumOutputStream cos = new ChecksumOutputStream(os, new CRC32());
        BufferedOutputStream bos = new BufferedOutputStream(cos);
        try {
            metadata.writeTo(new DataOutputStream(bos));
        } catch (MetadataUnresolvedException e) {
            throw new FetchException(FetchException.INTERNAL_ERROR, "Metadata not resolved starting splitfile fetch?!: "+e, e);
        }
        bos.close();
        long metadataLength = metadataTemp.size();
        offsetOriginalURI = offsetOriginalMetadata + metadataLength;
        
        byte[] encodedURI = encodeAndChecksumURI(thisKey);
        this.offsetBasicSettings = offsetOriginalURI + encodedURI.length;
        
        byte[] encodedBasicSettings = encodeBasicSettings(); // FIXME checksum???
        long totalLength = 
            offsetBasicSettings + // rest of file
            encodedBasicSettings.length + // basic settings
            4 + // length of basic settings
            4 + // version
            8; // magic
        
        // Create the actual LockableRandomAccessThing
        
        this.raf = rafFactory.makeRAF(totalLength);
        RAFLock lock = raf.lockOpen();
        try {
            for(SplitFileFetcherSegmentStorage segment : segments)
                segment.writeKeysWithChecksum();
            for(SplitFileFetcherSegmentStorage segment : segments)
                segment.writeMetadata();
            keyListener.innerWriteMainBloomFilter(offsetMainBloomFilter);
            keyListener.initialWriteSegmentBloomFilters(offsetSegmentBloomFilters);
            // TODO write metadata and then delete the temp bucket
            BucketTools.copyTo(metadataTemp, raf, offsetOriginalMetadata, -1);
            metadataTemp.free();
            raf.pwrite(offsetOriginalURI, encodedURI, 0, encodedURI.length);
            raf.pwrite(offsetBasicSettings, encodedBasicSettings, 0, encodedBasicSettings.length);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(encodedBasicSettings.length);
            dos.writeInt(VERSION);
            dos.writeLong(END_MAGIC);
            byte[] buf = baos.toByteArray();
            raf.pwrite(offsetBasicSettings + encodedBasicSettings.length, 
                    buf, 0, buf.length);
        } finally {
            lock.unlock();
        }
        if(logMINOR) Logger.minor(this, "Fetching "+thisKey+" on "+this);
    }
    
    /** Encode the basic settings (number of blocks etc) to a byte array */
    private byte[] encodeBasicSettings() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeShort(splitfileType);
            dos.writeByte(this.splitfileSingleCryptoAlgorithm);
            dos.writeBoolean(this.splitfileSingleCryptoKey != null);
            if(this.splitfileSingleCryptoKey != null) {
                assert(splitfileSingleCryptoKey.length == 32);
                dos.write(splitfileSingleCryptoKey);
            }
            dos.writeLong(this.finalLength);
            clientMetadata.writeTo(dos);
            dos.writeInt(decompressors.size());
            for(COMPRESSOR_TYPE c : decompressors)
                dos.writeShort(c.metadataID);
            dos.writeInt(segments.length);
            for(SplitFileFetcherSegmentStorage segment : segments) {
                segment.writeFixedMetadata(dos);
            }
            if(this.crossSegments == null)
                dos.writeInt(0);
            else {
                // FIXME
//                dos.writeInt(crossSegments.length);
//                for(SplitFileFetcherCrossSegmentStorage segment : crossSegments) {
//                    segment.writeFixedMetadata(dos);
//                }
            }
            dos.writeLong(offsetKeyList);
            dos.writeLong(offsetSegmentStatus);
            dos.writeLong(offsetMainBloomFilter);
            dos.writeLong(offsetSegmentBloomFilters);
            dos.writeLong(offsetOriginalMetadata);
            dos.writeLong(offsetOriginalURI);
            dos.writeLong(offsetBasicSettings);
        } catch (IOException e) {
            throw new Error(e); // Impossible
        }
        return baos.toByteArray();
    }

    /** Should work even for null key */
    private byte[] encodeAndChecksumURI(FreenetURI key) {
        byte[] data;
        if(key == null)
            data = new byte[0];
        else
            try {
                data = key.toString(true, false).getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                // Impossible.
                throw new Error(e);
            }
        return appendChecksum(data);
    }

    /** Append a CRC32 to a (short) byte[] */
    static private byte[] appendChecksum(byte[] data) {
        byte[] output = new byte[data.length+4];
        System.arraycopy(data, 0, output, 0, data.length);
        Checksum crc = new CRC32();
        crc.update(data, 0, data.length);
        byte[] checksum = Fields.intToBytes((int)crc.getValue());
        System.arraycopy(checksum, 0, output, data.length, 4);
        return output;
    }

    /** FIXME not used yet */
    private void allocateCrossDataBlock(SplitFileFetcherCrossSegmentStorage segment, Random random) {
        int x = 0;
        for(int i=0;i<10;i++) {
            x = random.nextInt(segments.length);
            SplitFileFetcherSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossDataBlock(segment, random);
            if(blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        for(int i=0;i<segments.length;i++) {
            x++;
            if(x == segments.length) x = 0;
            SplitFileFetcherSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossDataBlock(segment, random);
            if(blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        throw new IllegalStateException("Unable to allocate cross data block!");
    }

    /** FIXME not used yet */
    private void allocateCrossCheckBlock(SplitFileFetcherCrossSegmentStorage segment, Random random) {
        int x = 0;
        for(int i=0;i<10;i++) {
            x = random.nextInt(segments.length);
            SplitFileFetcherSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossCheckBlock(segment, random);
            if(blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        for(int i=0;i<segments.length;i++) {
            x++;
            if(x == segments.length) x = 0;
            SplitFileFetcherSegmentStorage seg = segments[x];
            int blockNum = seg.allocateCrossCheckBlock(segment, random);
            if(blockNum >= 0) {
                segment.addDataBlock(seg, blockNum);
                return;
            }
        }
        throw new IllegalStateException("Unable to allocate cross data block!");
    }


    
    public short getPriorityClass() {
        return fetcher.getPriorityClass();
    }

    /** A segment successfully completed. */
    public void finishedSuccess(SplitFileFetcherSegmentStorage splitFileFetcherSegmentStorage) {
        if(allSucceeded()) {
            executor.execute(new Runnable() {

                @Override
                public void run() {
                    fetcher.onSuccess();
                }
                
            });
        }
        // FIXME truncation optimisation: if the file isn't compressed and doesn't need filtering, 
        // we can just truncate it to get rid of the metadata etc.
        // If it is compressed or needs filtering, we may need to keep the metadata for now...
    }

    private boolean allSucceeded() {
        for(SplitFileFetcherSegmentStorage segment : segments) {
            if(!segment.hasSucceeded()) return false;
        }
        return true;
    }

    public StreamGenerator streamGenerator() {
        // FIXME truncation optimisation.
        return new StreamGenerator() {

            @Override
            public void writeTo(OutputStream os, ObjectContainer container, ClientContext context)
                    throws IOException {
                LockableRandomAccessThing.RAFLock lock = raf.lockOpen();
                try {
                    for(SplitFileFetcherSegmentStorage segment : segments) {
                        segment.writeToInner(os);
                    }
                    os.close();
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public long size() {
                return finalLength;
            }
            
        };
    }

    static final long LAZY_WRITE_METADATA_DELAY = TimeUnit.MINUTES.toMillis(5);
    
    private final Runnable writeMetadataJob = new Runnable() {

        @Override
        public void run() {
            try {
                RAFLock lock = raf.lockOpen();
                try {
                    for(SplitFileFetcherSegmentStorage segment : segments) {
                        segment.writeMetadata(false);
                    }
                    keyListener.maybeWriteMainBloomFilter(offsetMainBloomFilter);
                } finally {
                    lock.unlock();
                }
            } catch (IOException e) {
                Logger.error(this, "Failed writing metadata for "+this+": "+e, e);
                return;
            }
        }
        
    };

    public void lazyWriteMetadata() {
        ticker.queueTimedJob(writeMetadataJob, "Write metadata for splitfile", 
                LAZY_WRITE_METADATA_DELAY, false, true);
    }

    public void finishedFetcher() {
        synchronized(this) {
            finishedFetcher = true;
            if(!finishedEncoding) return;
        }
        executor.execute(new Runnable() {

            @Override
            public void run() {
                close();
            }
            
        });
    }
    
    private void finishedEncoding() {
        synchronized(this) {
            finishedEncoding = true;
            if(!finishedFetcher) return;
        }
        executor.execute(new Runnable() {

            @Override
            public void run() {
                close();
            }
            
        });
    }
    
    /** Shutdown and free resources. CONCURRENCY: Caller is responsible for making sure this is 
     * not called on a MemoryLimitedJob thread. */
    void close() {
        if(logMINOR) Logger.minor(this, "Finishing "+this+" for "+fetcher, new Exception("debug"));
        raf.close();
        raf.free();
        fetcher.onClosed();
    }
    
    void finishedEncoding(SplitFileFetcherSegmentStorage segment) {
        if(logMINOR) Logger.minor(this, "Successfully decoded "+segment+" for "+this+" for "+fetcher);
        if(!allFinished()) return;
        finishedEncoding();
    }
    
    private boolean allFinished() {
        for(SplitFileFetcherSegmentStorage segment : segments) {
            if(!segment.isFinished()) return false;
        }
        return true;
    }
    
    private boolean allDecodingOrFinished() {
        for(SplitFileFetcherSegmentStorage segment : segments) {
            if(!segment.isDecodingOrFinished()) return false;
        }
        return true;
    }

    public void failOnDiskError(final IOException e) {
        executor.execute(new Runnable() {

            @Override
            public void run() {
                fetcher.failOnDiskError(e);
            }
            
        });
    }

    public long countUnfetchedKeys() {
        long total = 0;
        for(SplitFileFetcherSegmentStorage segment : segments)
            total += segment.countUnfetchedKeys();
        return total;
    }

    public Key[] listUnfetchedKeys() {
        try {
            ArrayList<Key> keys = new ArrayList<Key>();
            for(SplitFileFetcherSegmentStorage segment : segments)
                segment.getUnfetchedKeys(keys);
            return keys.toArray(new Key[keys.size()]);
        } catch (IOException e) {
            failOnDiskError(e);
            return new Key[0];
        }
    }
    
    final class MyKey implements SendableRequestItem, SendableRequestItemKey {

        public MyKey(int n, int segNo, SplitFileFetcherStorage storage) {
            this.blockNumber = n;
            this.segmentNumber = segNo;
            this.get = storage;
            hashCode = initialHashCode();
        }

        final int blockNumber;
        final int segmentNumber;
        final SplitFileFetcherStorage get;
        final int hashCode;

        @Override
        public void dump() {
            // Ignore.
        }

        @Override
        public SendableRequestItemKey getKey() {
            return this;
        }
        
        @Override
        public boolean equals(Object o) {
            if(this == o) return true;
            if(!(o instanceof MyKey)) return false;
            MyKey k = (MyKey)o;
            return k.blockNumber == blockNumber && k.segmentNumber == segmentNumber && 
                k.get == get;
        }
        
        public int hashCode() {
            return hashCode;
        }

        private int initialHashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + blockNumber;
            result = prime * result + ((get == null) ? 0 : get.hashCode());
            result = prime * result + segmentNumber;
            return result;
        }

    }

    /** Choose a random key which can be fetched at the moment.
     * @return The block number to be fetched, as an integer.
     */
    public MyKey chooseRandomKey() {
        synchronized(this) {
            if(finishedFetcher) return null;
        }
        // Generally segments are fairly well balanced, so we can usually pick a random segment 
        // then a random key from it.
        int segNo = random.nextInt(segments.length);
        SplitFileFetcherSegmentStorage segment = segments[segNo];
        int ret = segment.chooseRandomKey();
        if(ret != -1) return new MyKey(ret, segNo, this);
        // Looks like we are close to completion ...
        // FIXME OPT SCALABILITY This is O(n) memory and time with the number of segments. For a 
        // 4GB splitfile, there would be 512 segments. The alternative is to keep a similar 
        // structure up to date, which also requires changes to the segment code. Not urgent until
        // very big splitfiles are common.
        boolean[] tried = new boolean[segments.length];
        tried[segNo] = true;
        for(int count=1; count<segments.length; count++) {
            while(tried[segNo = random.nextInt(segments.length)]);
            tried[segNo] = true;
            segment = segments[segNo];
            ret = segment.chooseRandomKey();
            if(ret != -1) return new MyKey(ret, segNo, this);
        }
        return null;
    }

    /** Cancel the download, stop all FEC decodes, and call close() off-thread when done. */
    void cancel() {
        synchronized(this) {
            cancelled = true;
            finishedFetcher = true;
        }
        for(SplitFileFetcherSegmentStorage segment : segments)
            segment.cancel();
    }

    /** Local only is true and we've finished checking the datastore. If all segments are not 
     * already finished/decoding, we need to fail with DNF. If the segments fail to decode due to
     * data corruption, we will retry as usual. */
    public void finishedCheckingDatastoreOnLocalRequest() {
        if(hasFinished()) return; // Don't need to do anything.
        if(allDecodingOrFinished()) {
            // All segments are decoding.
            // If they succeed, we complete.
            // If they fail to decode, they will cause the getter to retry, and re-scan the 
            // datastore for the corrupted keys.
            return;
        }
        if(hasFinished()) return; // Don't need to do anything.
        // Some segments still need data.
        // They're not going to get it.
        executor.execute(new Runnable() {

            @Override
            public void run() {
                fetcher.failCheckedDatastoreOnly();
            }
            
        });
    }

    private synchronized boolean hasFinished() {
        return cancelled || finishedFetcher;
    }

    public void onFailure(MyKey key, FetchException fe) {
        synchronized(this) {
            if(cancelled || finishedFetcher) return;
        }
        errors.inc(fe.getMode());
        SplitFileFetcherSegmentStorage segment = segments[key.segmentNumber];
        segment.onNonFatalFailure(key.blockNumber);
    }

    public ClientKey getKey(MyKey key) {
        try {
            return segments[key.segmentNumber].getSegmentKeys().getKey(key.blockNumber, null, false);
        } catch (IOException e) {
            this.failOnDiskError(e);
            return null;
        }
    }

    public int maxRetries() {
        return maxRetries;
    }

    /** A segment ran out of retries. We have given up on that segment and therefore on the whole
     * splitfile.
     * @param segment The segment that failed.
     */
    public void failOnSegment(SplitFileFetcherSegmentStorage segment) {
        executor.execute(new Runnable() {

            @Override
            public void run() {
                fetcher.fail(new FetchException(FetchException.SPLITFILE_ERROR, errors));
            }
            
        });
    }

    public void failedBlock() {
        executor.execute(new Runnable() {

            @Override
            public void run() {
                fetcher.onFailedBlock();
            }
            
        });
    }

}
