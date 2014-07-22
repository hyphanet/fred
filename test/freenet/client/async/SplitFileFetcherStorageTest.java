package freenet.client.async;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.MetadataUnresolvedException;
import freenet.client.OnionFECCodec;
import freenet.client.events.SimpleEventProducer;
import freenet.crypt.DummyRandomSource;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.support.Executor;
import freenet.support.MemoryLimitedJobRunner;
import freenet.support.PooledExecutor;
import freenet.support.Ticker;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.TempBucketFactory;
import junit.framework.TestCase;

public class SplitFileFetcherStorageTest extends TestCase {
    
    // Setup code is considerable. See below for actual tests ...
    
    static final DummyRandomSource random = new DummyRandomSource(1234);
    static final File tempDir = new File("split-file-fetcher-storage-test");
    static FilenameGenerator fg;
    static final KeySalter salt = new KeySalter() {

        @Override
        public byte[] saltKey(Key key) {
            return key.getRoutingKey();
        }
        
    };
    static BucketFactory bf;
    static final Executor exec = new PooledExecutor();
    static final Ticker ticker = new TrivialTicker(exec);
    static MemoryLimitedJobRunner memoryLimitedJobRunner = new MemoryLimitedJobRunner(9*1024*1024L, exec);
    static final int BLOCK_SIZE = CHKBlock.DATA_LENGTH;
    private static final OnionFECCodec codec = new OnionFECCodec();
    private static final int MAX_SEGMENT_SIZE = 256;
    static final int KEY_LENGTH = 32;
    static final short COMPATIBILITY_MODE = (short)InsertContext.CompatibilityMode.COMPAT_1416.ordinal();
    static final FreenetURI URI = FreenetURI.generateRandomCHK(random);
    private static final List<COMPRESSOR_TYPE> NO_DECOMPRESSORS = Collections.emptyList();
    
    public void setUp() throws IOException {
        fg = new FilenameGenerator(random, true, tempDir, "test-");
        bf = new TempBucketFactory(exec, fg, 0, 0, random, random, false);
    }

    static class TestSplitfile {
        final Bucket originalData;
        final Metadata metadata;
        final byte[][] dataBlocks;
        final byte[][] checkBlocks;
        final ClientCHK[] dataKeys;
        final ClientCHK[] checkKeys;
        private final byte[] cryptoKey;
        private final byte cryptoAlgorithm;
        
        private TestSplitfile(Bucket data, Metadata m, byte[][] originalDataBlocks,
                byte[][] originalCheckBlocks, ClientCHK[] dataKeys, ClientCHK[] checkKeys,
                byte[] cryptoKey, byte cryptoAlgorithm) {
            this.originalData = data;
            this.metadata = m;
            this.dataBlocks = originalDataBlocks;
            this.checkBlocks = originalCheckBlocks;
            this.dataKeys = dataKeys;
            this.checkKeys = checkKeys;
            this.cryptoKey = cryptoKey;
            this.cryptoAlgorithm = cryptoAlgorithm;
        }
        
        void free() {
            originalData.free();
        }

        static TestSplitfile constructSingleSegment(long size, int checkBlocks, String mime) throws IOException, CHKEncodeException, MetadataUnresolvedException, MetadataParseException {
            assertTrue(checkBlocks <= MAX_SEGMENT_SIZE);
            assertTrue(size < MAX_SEGMENT_SIZE * (long)BLOCK_SIZE);
            Bucket data = makeRandomBucket(size);
            byte[][] originalDataBlocks = splitAndPadBlocks(data, size);
            int dataBlocks = originalDataBlocks.length;
            assertTrue(dataBlocks <= MAX_SEGMENT_SIZE);
            assertTrue(dataBlocks + checkBlocks <= MAX_SEGMENT_SIZE);
            byte[][] originalCheckBlocks = constructBlocks(checkBlocks);
            codec.encode(originalDataBlocks, originalCheckBlocks, falseArray(checkBlocks), BLOCK_SIZE);
            ClientMetadata cm = new ClientMetadata(mime);
            // FIXME no hashes for now.
            // FIXME no compression for now.
            byte[] cryptoKey = randomKey();
            byte cryptoAlgorithm = Key.ALGO_AES_CTR_256_SHA256;
            ClientCHK[] dataKeys = makeKeys(originalDataBlocks, cryptoKey, cryptoAlgorithm);
            ClientCHK[] checkKeys = makeKeys(originalCheckBlocks, cryptoKey, cryptoAlgorithm);
            Metadata m = new Metadata(Metadata.SPLITFILE_ONION_STANDARD, dataKeys, checkKeys, dataBlocks, 
                    checkBlocks, 0, cm, size, null, null, size, false, null, null, size, size, dataBlocks, 
                    dataBlocks + checkBlocks, false, COMPATIBILITY_MODE, 
                    cryptoAlgorithm, cryptoKey, true, 0);
            // Make sure the metadata is reusable.
            // FIXME also necessary as the above constructor doesn't set segments.
            Bucket metaBucket = m.toBucket(bf);
            Metadata m1 = Metadata.construct(metaBucket);
            Bucket copyBucket = m1.toBucket(bf);
            assertTrue(BucketTools.equalBuckets(metaBucket, copyBucket));
            metaBucket.free();
            copyBucket.free();
            return new TestSplitfile(data, m1, originalDataBlocks, originalCheckBlocks, dataKeys, checkKeys, 
                    cryptoKey, cryptoAlgorithm);
        }
        
        public CHKBlock encodeDataBlock(int i) throws CHKEncodeException {
            return ClientCHKBlock.encodeSplitfileBlock(dataBlocks[i], cryptoKey, cryptoAlgorithm).getBlock();
        }
        
        public CHKBlock encodeCheckBlock(int i) throws CHKEncodeException {
            return ClientCHKBlock.encodeSplitfileBlock(checkBlocks[i], cryptoKey, cryptoAlgorithm).getBlock();
        }
        
        public CHKBlock encodeBlock(int block) throws CHKEncodeException {
            if(block < dataBlocks.length)
                return encodeDataBlock(block);
            else
                return encodeCheckBlock(block - dataBlocks.length);
        }

        public int findCheckBlock(byte[] data, int start) {
            start++;
            for(int i=start;i<checkBlocks.length;i++) {
                if(checkBlocks[i] == data) return i;
            }
            for(int i=start;i<checkBlocks.length;i++) {
                if(Arrays.equals(checkBlocks[i], data)) return i;
            }
            return -1;
        }

        public int findDataBlock(byte[] data, int start) {
            start++;
            for(int i=start;i<dataBlocks.length;i++) {
                if(dataBlocks[i] == data) return i;
            }
            for(int i=start;i<dataBlocks.length;i++) {
                if(Arrays.equals(dataBlocks[i], data)) return i;
            }
            return -1;
        }

        public StorageCallback createStorageCallback() {
            return new StorageCallback(this);
        }

        public SplitFileFetcherStorage createStorage(StorageCallback cb) throws FetchException, MetadataParseException, IOException {
            return new SplitFileFetcherStorage(metadata, cb, NO_DECOMPRESSORS, metadata.getClientMetadata(), false,
                    COMPATIBILITY_MODE, makeFetchContext(), false, salt, URI, random, bf,
                    fg, ticker, memoryLimitedJobRunner);
        }

        private FetchContext makeFetchContext() {
            return HighLevelSimpleClientImpl.makeDefaultFetchContext(Long.MAX_VALUE, Long.MAX_VALUE, 
                    bf, new SimpleEventProducer());
        }

        public void verifyOutput(SplitFileFetcherStorage storage) throws IOException {
            StreamGenerator g = storage.streamGenerator();
            Bucket out = bf.makeBucket(-1);
            OutputStream os = out.getOutputStream();
            g.writeTo(os, null, null);
            os.close();
            assertTrue(BucketTools.equalBuckets(originalData, out));
            out.free();
        }

        public NodeCHK getCHK(int block) {
            if(block < dataBlocks.length)
                return dataKeys[block].getNodeCHK();
            else
                return checkKeys[block-dataBlocks.length].getNodeCHK();
        }

    }
    
    public static ClientCHK[] makeKeys(byte[][] blocks, byte[] cryptoKey, byte cryptoAlgorithm) throws CHKEncodeException {
        ClientCHK[] keys = new ClientCHK[blocks.length];
        for(int i=0;i<blocks.length;i++)
            keys[i] = ClientCHKBlock.encodeSplitfileBlock(blocks[i], cryptoKey, cryptoAlgorithm).getClientKey();
        return keys;
    }
    
    static class StorageCallback implements SplitFileFetcherCallback {
        
        final TestSplitfile splitfile;
        final boolean[] encodedBlocks;
        private boolean succeeded;
        private boolean closed;
        private boolean failed;

        public StorageCallback(TestSplitfile splitfile) {
            this.splitfile = splitfile;
            encodedBlocks = new boolean[splitfile.dataBlocks.length + splitfile.checkBlocks.length];
        }

        @Override
        public synchronized void onSuccess() {
            succeeded = true;
            notifyAll();
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }

        @Override
        public short getPriorityClass() {
            return 0;
        }

        @Override
        public synchronized void failOnDiskError(IOException e) {
            failed = true;
            notifyAll();
            System.err.println("Failed on disk error: "+e);
            e.printStackTrace();
        }

        @Override
        public void setSplitfileBlocks(int requiredBlocks, int remainingBlocks) {
            assertEquals(requiredBlocks, splitfile.dataBlocks.length);
            assertEquals(remainingBlocks, splitfile.checkBlocks.length);
        }

        @Override
        public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max,
                byte[] customSplitfileKey, boolean compressed, boolean bottomLayer,
                boolean definitiveAnyway) {
            // Ignore. FIXME?
        }

        @Override
        public void queueHeal(byte[] data, byte[] cryptoKey, byte cryptoAlgorithm) {
            assertTrue(Arrays.equals(cryptoKey, splitfile.cryptoKey));
            assertEquals(cryptoAlgorithm, splitfile.cryptoAlgorithm);
            int x = -1;
            boolean progress = false;
            while((x = splitfile.findCheckBlock(data, x)) != -1) {
                encodedBlocks[x+splitfile.dataBlocks.length] = true;
                progress = true;
            }
            if(!progress) {
                // Data block?
                while((x = splitfile.findDataBlock(data, x)) != -1) {
                    encodedBlocks[x] = true;
                    progress = true;
                }
            }
            if(!progress) {
                System.err.println("Queued healing block not in the original block list");
                assertTrue(false);
            }
            assertTrue(progress);
        }

        public void checkFailed() {
            assertFalse(failed);
        }

        public synchronized void waitForFinished() {
            while(!(succeeded || failed)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
        
        public void waitForFree(SplitFileFetcherStorage storage) {
            synchronized(this) {
                while(!closed) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // Ignore.
                    }
                }
                assertTrue(succeeded);
            }
            int x = 0;
            for(SplitFileFetcherSegmentStorage seg : storage.segments) {
                boolean[] downloadedBlocks = seg.copyDownloadedBlocks();
                for(boolean b : downloadedBlocks) {
                    if(b) {
                        synchronized(this) {
                            encodedBlocks[x] = true;
                        }
                    }
                    x++;
                }
            }
            synchronized(this) {
                for(int i=0;i<encodedBlocks.length;i++)
                    assertTrue("Block "+i+" not found or decoded", encodedBlocks[i]);
            }
        }
        
    }

    public static Bucket makeRandomBucket(long size) throws IOException {
        Bucket b = bf.makeBucket(size);
        BucketTools.fill(b, random, size);
        return b;
    }

    public static byte[][] splitAndPadBlocks(Bucket data, long size) throws IOException {
        int n = (int) ((size + BLOCK_SIZE - 1) / BLOCK_SIZE);
        byte[][] blocks = new byte[n][];
        InputStream is = data.getInputStream();
        DataInputStream dis = new DataInputStream(is);
        for(int i=0;i<n;i++) {
            blocks[i] = new byte[BLOCK_SIZE];
            if(i < n-1) {
                dis.readFully(blocks[i]);
            } else {
                dis.readFully(blocks[i], 0, (int) (size - i*BLOCK_SIZE));
                // Now pad it ...
                blocks[i] = pad(blocks[i]);
            }
        }
        return blocks;
    }

    private static byte[] pad(byte[] orig) throws IOException {
        ArrayBucket b = new ArrayBucket(orig);
        Bucket ret = BucketTools.pad(b, BLOCK_SIZE, bf, orig.length);
        return BucketTools.toByteArray(ret);
    }

    public static byte[] randomKey() {
        byte[] buf = new byte[KEY_LENGTH];
        random.nextBytes(buf);
        return buf;
    }

    public static boolean[] falseArray(int checkBlocks) {
        return new boolean[checkBlocks];
    }

    public static byte[][] constructBlocks(int n) {
        byte[][] blocks = new byte[n][];
        for(int i=0;i<n;i++) blocks[i] = new byte[BLOCK_SIZE];
        return blocks;
    }
    
    // Actual tests ...
    
    public void testSingleSegment() throws CHKEncodeException, IOException, FetchException, MetadataParseException, MetadataUnresolvedException {
        // 2 data blocks.
        testSingleSegment(2, 1, BLOCK_SIZE*2);
        testSingleSegment(2, 1, BLOCK_SIZE+1);
        testSingleSegment(2, 2, BLOCK_SIZE*2);
        testSingleSegment(2, 2, BLOCK_SIZE+1);
        testSingleSegment(2, 3, BLOCK_SIZE*2);
        testSingleSegment(2, 3, BLOCK_SIZE+1);
        testSingleSegment(128, 128, BLOCK_SIZE*128);
        testSingleSegment(128, 128, BLOCK_SIZE*128-1);
        testSingleSegment(129, 127, BLOCK_SIZE*129);
        testSingleSegment(129, 127, BLOCK_SIZE*129-1);
        testSingleSegment(127, 129, BLOCK_SIZE*127);
        testSingleSegment(127, 129, BLOCK_SIZE*127-1);
    }
    
    // FIXME test multiple segments.
    
    // FIXME LATER Test cross-segment.

    private void testSingleSegment(int dataBlocks, int checkBlocks, long size) throws CHKEncodeException, IOException, FetchException, MetadataParseException, MetadataUnresolvedException {
        assertTrue(dataBlocks * (long)BLOCK_SIZE >= size);
        TestSplitfile test = TestSplitfile.constructSingleSegment(size, checkBlocks, null);
        testDataBlocksOnly(test);
        if(checkBlocks >= dataBlocks)
            testCheckBlocksOnly(test);
        testRandomMixture(test);
        test.free();
    }

    private void testDataBlocksOnly(TestSplitfile test) throws IOException, CHKEncodeException, FetchException, MetadataParseException {
        StorageCallback cb = test.createStorageCallback();
        SplitFileFetcherStorage storage = test.createStorage(cb);
        SplitFileFetcherSegmentStorage segment = storage.segments[0];
        for(int i=0;i<test.checkBlocks.length;i++) {
            segment.onNonFatalFailure(test.dataBlocks.length+i);
        }
        for(int i=0;i<test.dataBlocks.length;i++) {
            assertFalse(segment.hasStartedDecode());
            assertTrue(segment.onGotKey(test.dataKeys[i].getNodeCHK(), test.encodeDataBlock(i)));
        }
        cb.checkFailed();
        assertTrue(segment.hasStartedDecode());
        cb.checkFailed();
        waitForDecode(segment);
        cb.checkFailed();
        cb.waitForFinished();
        cb.checkFailed();
        test.verifyOutput(storage);
        cb.checkFailed();
        storage.finishedFetcher();
        cb.checkFailed();
        waitForFinished(segment);
        cb.checkFailed();
        cb.waitForFree(storage);
        cb.checkFailed();
    }

    private void testCheckBlocksOnly(TestSplitfile test) throws IOException, CHKEncodeException, FetchException, MetadataParseException {
        StorageCallback cb = test.createStorageCallback();
        SplitFileFetcherStorage storage = test.createStorage(cb);
        SplitFileFetcherSegmentStorage segment = storage.segments[0];
        for(int i=0;i<test.dataBlocks.length;i++) {
            segment.onNonFatalFailure(i);
        }
        for(int i=test.dataBlocks.length;i<test.checkBlocks.length;i++) {
            segment.onNonFatalFailure(i+test.dataBlocks.length);
        }
        for(int i=0;i<test.dataBlocks.length /* only need that many to decode */;i++) {
            assertFalse(segment.hasStartedDecode());
            assertTrue(segment.onGotKey(test.checkKeys[i].getNodeCHK(), test.encodeCheckBlock(i)));
        }
        cb.checkFailed();
        assertTrue(segment.hasStartedDecode());
        cb.checkFailed();
        waitForDecode(segment);
        cb.checkFailed();
        cb.waitForFinished();
        cb.checkFailed();
        test.verifyOutput(storage);
        cb.checkFailed();
        storage.finishedFetcher();
        cb.checkFailed();
        waitForFinished(segment);
        cb.checkFailed();
        cb.waitForFree(storage);
        cb.checkFailed();
    }
    
    private void testRandomMixture(TestSplitfile test) throws FetchException, MetadataParseException, IOException, CHKEncodeException {
        StorageCallback cb = test.createStorageCallback();
        SplitFileFetcherStorage storage = test.createStorage(cb);
        SplitFileFetcherSegmentStorage segment = storage.segments[0];
        int total = test.dataBlocks.length+test.checkBlocks.length;
        for(int i=0;i<total;i++)
            segment.onNonFatalFailure(i); // We want healing on all blocks that aren't found.
        boolean[] hits = new boolean[total];
        for(int i=0;i<test.dataBlocks.length;i++) {
            int block;
            do {
                block = random.nextInt(total);
            } while (hits[block]);
            hits[block] = true;
            assertFalse(segment.hasStartedDecode());
            assertTrue(segment.onGotKey(test.getCHK(block), test.encodeBlock(block)));
        }
        //printChosenBlocks(hits);
        cb.checkFailed();
        assertTrue(segment.hasStartedDecode());
        cb.checkFailed();
        waitForDecode(segment);
        cb.checkFailed();
        cb.waitForFinished();
        cb.checkFailed();
        test.verifyOutput(storage);
        cb.checkFailed();
        storage.finishedFetcher();
        cb.checkFailed();
        waitForFinished(segment);
        cb.checkFailed();
        cb.waitForFree(storage);
        cb.checkFailed();
    }

    private void printChosenBlocks(boolean[] hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("Blocks: ");
        for(int i=0;i<hits.length;i++) {
            if(hits[i]) {
                sb.append(i);
                sb.append(" ");
            }
        }
        sb.setLength(sb.length()-1);
        System.out.println(sb.toString());
    }

    private void waitForFinished(SplitFileFetcherSegmentStorage segment) {
        while(!segment.isFinished()) {
            assertFalse(segment.hasFailed());
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }
    }

    private void waitForDecode(SplitFileFetcherSegmentStorage segment) {
        while(!segment.hasSucceeded()) {
            assertFalse(segment.hasFailed());
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }
    }
}
