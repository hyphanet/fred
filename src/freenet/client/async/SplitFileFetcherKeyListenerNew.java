package freenet.client.async;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.crypt.SHA256;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.node.SendableGet;
import freenet.support.BinaryBloomFilter;
import freenet.support.BloomFilter;
import freenet.support.CountingBloomFilter;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.io.LockableRandomAccessThing.RAFLock;

public class SplitFileFetcherKeyListenerNew implements KeyListener {
    
    private boolean logMINOR;
    static {
        Logger.registerClass(SplitFileFetcherKeyListenerNew.class);
    }
    
    final SplitFileFetcherStorage storage;
    final SplitFileFetcherNew fetcher;
    /** Salt used in the secondary Bloom filters if the primary matches.
     * The primary Bloom filters use the already-salted saltedKey. */
    private final byte[] localSalt;
    /** Size of the main Bloom filter in bytes. */
    private final int mainBloomFilterSizeBytes;
    /** Default mainBloomElementsPerKey. False positives is approx
     * 0.6185^[this number], so 19 gives us 0.01% false positives, which should
     * be acceptable even if there are thousands of splitfiles on the queue. */
    static final int DEFAULT_MAIN_BLOOM_ELEMENTS_PER_KEY = 19;
    /** Number of hashes for the main filter. */
    private final int mainBloomK;
    /** What proportion of false positives is acceptable for the per-segment
     * Bloom filters? This is divided by the number of segments, so it is (roughly)
     * an overall probability of any false positive given that we reach the
     * per-segment filters. IMHO 1 in 100 is adequate. */
    static final double ACCEPTABLE_BLOOM_FALSE_POSITIVES_ALL_SEGMENTS = 0.01;
    /** Size of per-segment bloom filter in bytes. This is calculated from the
     * above constant and the number of segments, and rounded up. */
    private final int perSegmentBloomFilterSizeBytes;
    /** Number of hashes for the per-segment bloom filters. */
    private final int perSegmentK;
    /** Number of keys we are still waiting for. FIXME This should be created on startup from the
     * splitfile metadata. */
    private int keyCount;
    /** The overall bloom filter, containing all the keys, salted with the global hash. When a key
     * is found, it is removed from this. */
    private final CountingBloomFilter filter;
    /** The per-segment bloom filters, containing the keys for each segment. These are not changed. */
    private final BinaryBloomFilter[] segmentFilters;
    private boolean finishedSetup;
    private final boolean realTime;
    private final boolean persistent;
    /** Does the main bloom filter need writing? */
    private boolean dirty;
    
    /** Create a set of bloom filters for a new download.
     * @throws FetchException */
    public SplitFileFetcherKeyListenerNew(SplitFileFetcherNew fetcher, SplitFileFetcherStorage storage, 
            boolean realTime, boolean persistent, byte[] localSalt, int origSize, int segBlocks, 
            int segments) throws FetchException {
        this.fetcher = fetcher;
        this.storage = storage;
        this.localSalt = localSalt;
        this.realTime = realTime;
        this.persistent = persistent;
        int mainElementsPerKey = DEFAULT_MAIN_BLOOM_ELEMENTS_PER_KEY;
        mainBloomK = (int) (mainElementsPerKey * 0.7);
        long elementsLong = origSize * mainElementsPerKey;
        // REDFLAG: SIZE LIMIT: 3.36TB limit!
        if(elementsLong > Integer.MAX_VALUE)
            throw new FetchException(FetchException.TOO_BIG, "Cannot fetch splitfiles with more than "+(Integer.MAX_VALUE/mainElementsPerKey)+" keys! (approx 3.3TB)");
        int mainSizeBits = (int)elementsLong; // counting filter
        mainSizeBits = (mainSizeBits + 7) & ~7; // round up to bytes
        mainBloomFilterSizeBytes = mainSizeBits / 8 * 2; // counting filter
        double acceptableFalsePositives = ACCEPTABLE_BLOOM_FALSE_POSITIVES_ALL_SEGMENTS / segments;
        int perSegmentBitsPerKey = (int) Math.ceil(Math.log(acceptableFalsePositives) / Math.log(0.6185));
        if(segBlocks > origSize)
            segBlocks = origSize;
        int perSegmentSize = perSegmentBitsPerKey * segBlocks;
        perSegmentSize = (perSegmentSize + 7) & ~7;
        perSegmentBloomFilterSizeBytes = perSegmentSize / 8;
        perSegmentK = BloomFilter.optimialK(perSegmentSize, segBlocks);
        keyCount = origSize;
        segmentFilters = new BinaryBloomFilter[segments];
        byte[] segmentsFilterBuffer = new byte[perSegmentBloomFilterSizeBytes * segments];
        ByteBuffer baseBuffer = ByteBuffer.wrap(segmentsFilterBuffer);
        int start = 0;
        int end = perSegmentBloomFilterSizeBytes;
        for(int i=0;i<segments;i++) {
            baseBuffer.position(start);
            baseBuffer.limit(end);
            ByteBuffer slice;
            
            slice = baseBuffer.slice();
            segmentFilters[i] = new BinaryBloomFilter(slice, perSegmentBloomFilterSizeBytes * 8, perSegmentK);
            start += perSegmentBloomFilterSizeBytes;
            end += perSegmentBloomFilterSizeBytes;
        }
        byte[] filterBuffer = new byte[mainBloomFilterSizeBytes];
        filter = new CountingBloomFilter(mainBloomFilterSizeBytes * 8 / 2, mainBloomK, filterBuffer);
        filter.setWarnOnRemoveFromEmpty();
    }
    
    /**
     * SplitFileFetcher adds keys in whatever blocks are convenient.
     * @param keys
     */
    synchronized void addKey(Key key, int segNo, ClientContext context, ClientRequestSchedulerBase scheduler) {
        if(finishedSetup) throw new IllegalStateException();
        byte[] saltedKey = scheduler.saltKey(key);
        filter.addKey(saltedKey);
        byte[] localSalted = localSaltKey(key);
        segmentFilters[segNo].addKey(localSalted);
//      if(!segmentFilters[segNo].checkFilter(localSalted))
//          Logger.error(this, "Key added but not in filter: "+key+" on "+this);
    }
    
    synchronized void finishedSetup() {
        finishedSetup = true;
    }

    private byte[] localSaltKey(Key key) {
        MessageDigest md = SHA256.getMessageDigest();
        md.update(key.getRoutingKey());
        md.update(localSalt);
        byte[] ret = md.digest();
        SHA256.returnMessageDigest(md);
        return ret;
    }
    
    /** The segment bloom filters should only need to be written ONCE, and can all be written at 
     * once. Include a checksum. */
    void initialWriteSegmentBloomFilters(long fileOffset) throws IOException {
        synchronized(this) {
            if(!finishedSetup) throw new IllegalStateException();
        }
        byte[] buf = new byte[totalSegmentBloomFiltersSize()];
        int offset = 0;
        for(BinaryBloomFilter segFilter : segmentFilters) {
            int written = segFilter.copyTo(buf, offset);
            assert(written == perSegmentBloomFilterSizeBytes);
            offset += written;
        }
        CRC32 checksum = new CRC32();
        checksum.update(buf, 0, offset);
        byte[] out = Fields.intToBytes((int)checksum.getValue());
        System.arraycopy(out, 0, buf, offset, 4);
        assert(offset + 4 == buf.length);
        RAFLock lock = storage.raf.lock();
        try {
            storage.raf.pwrite(fileOffset, buf, 0, buf.length);
        } finally {
            lock.unlock();
        }        
    }
    
    int totalSegmentBloomFiltersSize() {
        return perSegmentBloomFilterSizeBytes * segmentFilters.length + 4;
    }
    
    void maybeWriteMainBloomFilter(long fileOffset) throws IOException {
        synchronized(this) {
            if(!dirty) return;
            dirty = false;
        }
        innerWriteMainBloomFilter(fileOffset);
    }

    /** Write the main segment filter, which does get updated. Include a checksum. */
    void innerWriteMainBloomFilter(long fileOffset) throws IOException {
        synchronized(this) {
            if(!finishedSetup) throw new IllegalStateException();
        }
        byte[] buf = new byte[paddedMainBloomFilterSize()];
        int offset = filter.copyTo(buf, 0);
        assert(offset == mainBloomFilterSizeBytes);
        CRC32 checksum = new CRC32();
        checksum.update(buf, 0, offset);
        byte[] out = Fields.intToBytes((int)checksum.getValue());
        System.arraycopy(out, 0, buf, offset, 4);
        assert(offset + 4 == buf.length);
        
        RAFLock lock = storage.raf.lock();
        try {
            storage.raf.pwrite(fileOffset, buf, 0, buf.length);
        } finally {
            lock.unlock();
        }
    }
    
    public int paddedMainBloomFilterSize() {
        assert(mainBloomFilterSizeBytes == filter.getSizeBytes());
        return mainBloomFilterSizeBytes + 4;
    }

    @Override
    public boolean probablyWantKey(Key key, byte[] saltedKey) {
        if(filter.checkFilter(saltedKey)) {
            byte[] salted = localSaltKey(key);
            for(int i=0;i<segmentFilters.length;i++) {
                if(segmentFilters[i].checkFilter(salted)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public short definitelyWantKey(Key key, byte[] saltedKey, ObjectContainer container,
            ClientContext context) {
        // Caller has already called probablyWantKey(), so don't do it again.
        byte[] salted = localSaltKey(key);
        for(int i=0;i<segmentFilters.length;i++) {
            if(segmentFilters[i].checkFilter(salted)) {
                if(storage.segments[i].definitelyWantKey((NodeCHK)key))
                    return fetcher.getPriorityClass();
            }
        }
        return -1;
    }

    @Override
    public SendableGet[] getRequestsForKey(Key key, byte[] saltedKey, ObjectContainer container,
            ClientContext context) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean handleBlock(Key key, byte[] saltedKey, KeyBlock block,
            ObjectContainer container, ClientContext context) {
        // Caller has already called probablyWantKey(), so don't do it again.
        boolean found = false;
        byte[] salted = localSaltKey(key);
        if(logMINOR)
            Logger.minor(this, "handleBlock("+key+") on "+this+" for "+fetcher);
        for(int i=0;i<segmentFilters.length;i++) {
            boolean match;
            synchronized(this) {
                match = segmentFilters[i].checkFilter(salted);
            }
            if(match) {
                try {
                    while(storage.segments[i].onGotKey((NodeCHK)key, (CHKBlock)block)) {
                        found = true;
                    }
                } catch (IOException e) {
                    fetcher.failOnDiskError(e, storage.context);
                    return false;
                }
            }
        }
        if(found) {
            synchronized(this) {
                dirty = true;
                keyCount--;
            }
            filter.removeKey(saltedKey);
            if(persistent)
                storage.lazyWriteMetadata();
        }
        return found;
    }

    @Override
    public boolean persistent() {
        return persistent;
    }

    @Override
    public short getPriorityClass(ObjectContainer container) {
        return fetcher.getPriorityClass();
    }

    @Override
    public synchronized long countKeys() {
        return keyCount;
    }

    @Override
    public HasKeyListener getHasKeyListener() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void onRemove() {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isEmpty() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isSSK() {
        return false;
    }

    @Override
    public boolean isRealTime() {
        return realTime;
    }

}
