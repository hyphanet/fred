package freenet.client.async;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

import junit.framework.TestCase;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.async.SplitFileInserterSegmentStorage.BlockInsert;
import freenet.client.async.SplitFileInserterSegmentStorage.MissingKeyException;
import freenet.client.async.SplitFileInserterStorage.Status;
import freenet.client.events.SimpleEventProducer;
import freenet.crypt.CRCChecksumChecker;
import freenet.crypt.ChecksumChecker;
import freenet.crypt.ChecksumFailedException;
import freenet.crypt.DummyRandomSource;
import freenet.crypt.HashResult;
import freenet.crypt.HashType;
import freenet.crypt.MultiHashInputStream;
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.SendableInsert;
import freenet.node.SendableRequestItemKey;
import freenet.support.CheatingTicker;
import freenet.support.DummyJobRunner;
import freenet.support.MemoryLimitedJobRunner;
import freenet.support.PooledExecutor;
import freenet.support.TestProperty;
import freenet.support.Ticker;
import freenet.support.WaitableExecutor;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BarrierRandomAccessThing;
import freenet.support.io.BucketTools;
import freenet.support.io.ByteArrayRandomAccessThingFactory;
import freenet.support.io.FileUtil;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.LockableRandomAccessThing;
import freenet.support.io.LockableRandomAccessThingFactory;
import freenet.support.io.NullOutputStream;
import freenet.support.io.PersistentFileTracker;
import freenet.support.io.PooledFileRandomAccessThingFactory;
import freenet.support.io.RAFBucket;
import freenet.support.io.RAFInputStream;
import freenet.support.io.ReadOnlyRandomAccessThing;
import freenet.support.io.ResumeFailedException;
import freenet.support.io.StorageFormatException;
import freenet.support.io.TempBucketFactory;
import freenet.support.io.TrivialPersistentFileTracker;

public class SplitFileInserterStorageTest extends TestCase {
    
    final LockableRandomAccessThingFactory smallRAFFactory = new ByteArrayRandomAccessThingFactory();
    final FilenameGenerator fg;
    final PersistentFileTracker persistentFileTracker;
    final LockableRandomAccessThingFactory bigRAFFactory;
    final BucketFactory smallBucketFactory;
    final BucketFactory bigBucketFactory;
    final File dir;
    final InsertContext baseContext;
    final WaitableExecutor executor;
    final Ticker ticker;
    final byte cryptoAlgorithm = Key.ALGO_AES_CTR_256_SHA256;
    final byte[] cryptoKey;
    final ChecksumChecker checker;
    final MemoryLimitedJobRunner memoryLimitedJobRunner;
    final PersistentJobRunner jobRunner;
    final KeySalter salt = new KeySalter() {

        @Override
        public byte[] saltKey(Key key) {
            return key.getRoutingKey();
        }
        
    };
    private final FreenetURI URI;
    
    public SplitFileInserterStorageTest() throws IOException {
        dir = new File("split-file-inserter-storage-test");
        dir.mkdir();
        executor = new WaitableExecutor(new PooledExecutor());
        ticker = new CheatingTicker(executor);
        RandomSource r = new DummyRandomSource(12345);
        fg = new FilenameGenerator(r, true, dir, "freenet-test");
        persistentFileTracker = new TrivialPersistentFileTracker(dir, fg);
        bigRAFFactory = new PooledFileRandomAccessThingFactory(fg, r);
        smallBucketFactory = new ArrayBucketFactory();
        bigBucketFactory = new TempBucketFactory(executor, fg, 0, 0, r, r, false, 0);
        baseContext = HighLevelSimpleClientImpl.makeDefaultInsertContext(bigBucketFactory, new SimpleEventProducer());
        cryptoKey = new byte[32];
        r.nextBytes(cryptoKey);
        checker = new CRCChecksumChecker();
        memoryLimitedJobRunner = new MemoryLimitedJobRunner(9*1024*1024L, executor);
        jobRunner = new DummyJobRunner(executor, null);
        URI = FreenetURI.generateRandomCHK(r);
    }
    
    class MyCallback implements SplitFileInserterStorageCallback {
        
        private boolean finishedEncode;
        private boolean hasKeys;
        private boolean succeededInsert;
        private InsertException failed;
        private Metadata metadata;

        @Override
        public synchronized void onFinishedEncode() {
            finishedEncode = true;
            notifyAll();
        }

        @Override
        public synchronized void onHasKeys() {
            hasKeys = true;
            notifyAll();
        }
        
        @Override
        public void encodingProgress() {
            // Ignore.
        }
        
        private void checkFailed() throws InsertException {
            if(failed != null)
                throw failed;
        }

        public synchronized void waitForFinishedEncode() throws InsertException {
            while(!finishedEncode) {
                checkFailed();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }

        public synchronized void waitForHasKeys() throws InsertException {
            while(!hasKeys) {
                checkFailed();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }

        @Override
        public synchronized void onSucceeded(Metadata metadata) {
            succeededInsert = true;
            this.metadata = metadata;
            notifyAll();
        }
        
        public synchronized Metadata waitForSucceededInsert() throws InsertException {
            while(!succeededInsert) {
                checkFailed();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
            return metadata;
        }

        @Override
        public synchronized void onFailed(InsertException e) {
            failed = e;
            notifyAll();
        }

        @Override
        public SendableInsert getSendableInsert() {
            return null;
        }

        @Override
        public void onInsertedBlock() {
            // Ignore.
        }

    }
    
    public void testSmallSplitfileNoLastBlock() throws IOException, InsertException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, baseContext.clone(), 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        assertEquals(storage.segments[0].dataBlockCount, 2);
        assertEquals(storage.segments[0].checkBlockCount, 3);
        assertEquals(storage.segments[0].crossCheckBlockCount, 0);
        assertTrue(storage.getStatus() == Status.ENCODED);
    }

    public void testSmallSplitfileWithLastBlock() throws IOException, InsertException {
        Random r = new Random(12122);
        long size = 65535;
        byte[] originalData = new byte[(int)size];
        r.nextBytes(originalData);
        LockableRandomAccessThing data = smallRAFFactory.makeRAF(originalData, 0, originalData.length);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, baseContext.clone(), 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        // Now check the data blocks...
        assertEquals(storage.segments.length, 1);
        assertEquals(storage.segments[0].dataBlockCount, 2);
        assertEquals(storage.segments[0].checkBlockCount, 3);
        assertEquals(storage.segments[0].crossCheckBlockCount, 0);
        assertTrue(Arrays.equals(storage.readSegmentDataBlock(0, 0), Arrays.copyOfRange(originalData, 0, CHKBlock.DATA_LENGTH)));
        int truncateLength = (int) (size % CHKBlock.DATA_LENGTH);
        long offsetLastBlock = size - truncateLength;
        byte[] buf = storage.readSegmentDataBlock(0, 1);
        assert(buf.length == CHKBlock.DATA_LENGTH);
        byte[] truncated = Arrays.copyOfRange(buf, 0, truncateLength);
        byte[] originalLastBlock = Arrays.copyOfRange(originalData, (int)offsetLastBlock, originalData.length);
        assertEquals(originalLastBlock.length, truncated.length);
        assertTrue(Arrays.equals(originalLastBlock, truncated));
        assertTrue(storage.getStatus() == Status.ENCODED);
    }
    
    public void testSmallSplitfileHasKeys() throws IOException, InsertException, MissingKeyException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.earlyEncode = true;
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        assertEquals(storage.segments[0].dataBlockCount, 2);
        assertEquals(storage.segments[0].checkBlockCount, 3);
        assertEquals(storage.segments[0].crossCheckBlockCount, 0);
        cb.waitForHasKeys();
        for(int i=0;i<storage.segments[0].dataBlockCount+storage.segments[0].checkBlockCount+storage.segments[0].crossCheckBlockCount;i++)
            storage.segments[0].readKey(i);
        storage.encodeMetadata();
        assertTrue(storage.getStatus() == Status.ENCODED);
    }

    public void testSmallSplitfileCompletion() throws IOException, InsertException, MissingKeyException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        assertEquals(segment.dataBlockCount, 2);
        assertEquals(segment.checkBlockCount, 3);
        assertEquals(segment.crossCheckBlockCount, 0);
        assertEquals(storage.getStatus(), Status.ENCODED);
        for(int i=0;i<segment.totalBlockCount;i++) {
            segment.onInsertedBlock(i, segment.encodeBlock(i).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(storage.getStatus(), Status.SUCCEEDED);
    }

    public void testSmallSplitfileChooseCompletion() throws IOException, InsertException, MissingKeyException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.maxInsertRetries = 2;
        MyKeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        assertEquals(segment.dataBlockCount, 2);
        assertEquals(segment.checkBlockCount, 3);
        assertEquals(segment.crossCheckBlockCount, 0);
        assertEquals(storage.getStatus(), Status.ENCODED);
        boolean[] chosenBlocks = new boolean[segment.totalBlockCount];
        // Choose and fail all blocks.
        for(int i=0;i<segment.totalBlockCount;i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertTrue(chosen != null);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onFailure(chosen.blockNumber, new InsertException(InsertException.ROUTE_NOT_FOUND));
        }
        keys.clear();
        // Choose and succeed all blocks.
        chosenBlocks = new boolean[segment.totalBlockCount];
        for(int i=0;i<segment.totalBlockCount;i++) {
            BlockInsert chosen = segment.chooseBlock();
            keys.addInsert(chosen);
            assertTrue(chosen != null);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onInsertedBlock(chosen.blockNumber, segment.encodeBlock(chosen.blockNumber).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(storage.getStatus(), Status.SUCCEEDED);
    }

    public void testSmallSplitfileConsecutiveRNFsHack() throws IOException, InsertException, MissingKeyException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.maxInsertRetries = 0;
        context.consecutiveRNFsCountAsSuccess = 2;
        MyKeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        assertEquals(segment.dataBlockCount, 2);
        assertEquals(segment.checkBlockCount, 3);
        assertEquals(segment.crossCheckBlockCount, 0);
        assertEquals(storage.getStatus(), Status.ENCODED);
        boolean[] chosenBlocks = new boolean[segment.totalBlockCount];
        // First RNF.
        for(int i=0;i<segment.totalBlockCount;i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertTrue(chosen != null);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.setKey(chosen.blockNumber, segment.encodeBlock(chosen.blockNumber).getClientKey());
            segment.onFailure(chosen.blockNumber, new InsertException(InsertException.ROUTE_NOT_FOUND));
        }
        chosenBlocks = new boolean[segment.totalBlockCount];
        // Second RNF.
        keys.clear();
        for(int i=0;i<segment.totalBlockCount;i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertTrue(chosen != null);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onFailure(chosen.blockNumber, new InsertException(InsertException.ROUTE_NOT_FOUND));
        }
        // Should count as success at this point.
        cb.waitForSucceededInsert();
        assertEquals(storage.getStatus(), Status.SUCCEEDED);
    }

    public void testSmallSplitfileConsecutiveRNFsHackFailure() throws IOException, InsertException, MissingKeyException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        // Do 2 RNFs and then a RejectedOverload. Should fail at that point.
        context.maxInsertRetries = 2;
        context.consecutiveRNFsCountAsSuccess = 3;
        MyKeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        assertEquals(segment.dataBlockCount, 2);
        assertEquals(segment.checkBlockCount, 3);
        assertEquals(segment.crossCheckBlockCount, 0);
        assertEquals(storage.getStatus(), Status.ENCODED);
        segment.setKey(0, segment.encodeBlock(0).getClientKey());
        segment.onFailure(0, new InsertException(InsertException.ROUTE_NOT_FOUND));
        assertEquals(storage.getStatus(), Status.ENCODED);
        segment.onFailure(0, new InsertException(InsertException.ROUTE_NOT_FOUND));
        assertEquals(storage.getStatus(), Status.ENCODED);
        segment.onFailure(0, new InsertException(InsertException.REJECTED_OVERLOAD));
        // Should count as success at this point.
        try {
            cb.waitForSucceededInsert();
            assertTrue(false);
        } catch (InsertException e) {
            // Expected.
            assertEquals(e.mode, InsertException.TOO_MANY_RETRIES_IN_BLOCKS);
            assertTrue(e.errorCodes != null);
            assertEquals(e.errorCodes.getErrorCount(InsertException.ROUTE_NOT_FOUND), 2);
            assertEquals(e.errorCodes.getErrorCount(InsertException.REJECTED_OVERLOAD), 1);
            assertEquals(e.errorCodes.totalCount(), 3);
            assertEquals(storage.getStatus(), Status.FAILED);
        }
    }

    public void testSmallSplitfileFailureMaxRetries() throws IOException, InsertException, MissingKeyException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.consecutiveRNFsCountAsSuccess = 0;
        context.maxInsertRetries = 2;
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        assertEquals(segment.dataBlockCount, 2);
        assertEquals(segment.checkBlockCount, 3);
        assertEquals(segment.crossCheckBlockCount, 0);
        assertEquals(storage.getStatus(), Status.ENCODED);
        for(int i=0;i<3;i++) {
            segment.onFailure(0, new InsertException(InsertException.ROUTE_NOT_FOUND));
        }
        try {
            cb.waitForSucceededInsert();
            assertTrue(false);
        } catch (InsertException e) {
            assertEquals(e.mode, InsertException.TOO_MANY_RETRIES_IN_BLOCKS);
            assertTrue(e.errorCodes != null);
            assertEquals(e.errorCodes.getErrorCount(InsertException.ROUTE_NOT_FOUND), 3);
            assertEquals(e.errorCodes.totalCount(), 3);
            assertEquals(storage.getStatus(), Status.FAILED);
        }
    }

    public void testSmallSplitfileFailureFatalError() throws IOException, InsertException, MissingKeyException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.maxInsertRetries = 2;
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        assertEquals(segment.dataBlockCount, 2);
        assertEquals(segment.checkBlockCount, 3);
        assertEquals(segment.crossCheckBlockCount, 0);
        assertEquals(storage.getStatus(), Status.ENCODED);
        assertTrue(InsertException.isFatal(InsertException.INTERNAL_ERROR));
        segment.onFailure(0, new InsertException(InsertException.INTERNAL_ERROR));
        try {
            cb.waitForSucceededInsert();
            assertTrue(false);
        } catch (InsertException e) {
            assertEquals(e.mode, InsertException.FATAL_ERRORS_IN_BLOCKS);
            assertTrue(e.errorCodes != null);
            assertEquals(e.errorCodes.getErrorCount(InsertException.INTERNAL_ERROR), 1);
            assertEquals(e.errorCodes.totalCount(), 1);
            assertEquals(storage.getStatus(), Status.FAILED);
        }
    }

    private HashResult[] getHashes(LockableRandomAccessThing data) throws IOException {
        InputStream is = new RAFInputStream(data, 0, data.size());
        MultiHashInputStream hashStream = new MultiHashInputStream(is, HashType.SHA256.bitmask);
        FileUtil.copy(is, new NullOutputStream(), data.size());
        is.close();
        return hashStream.getResults();
    }

    private LockableRandomAccessThing generateData(Random random, long size,
            LockableRandomAccessThingFactory smallRAFFactory) throws IOException {
        LockableRandomAccessThing thing = smallRAFFactory.makeRAF(size);
        BucketTools.fill(thing, random, 0, size);
        return new ReadOnlyRandomAccessThing(thing);
    }
    
    public void testRoundTripSimple() throws FetchException, MetadataParseException, Exception {
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH*2);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH*2-1);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH*128);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH*128+1);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH*192);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH*192+1);
    }
    
    public void testRoundTripCrossSegment() throws IOException, InsertException, MissingKeyException, FetchException, MetadataParseException, Exception {
        if(!TestProperty.EXTENSIVE) return;
        // Test cross-segment:
        testRoundTripCrossSegmentRandom(CHKBlock.DATA_LENGTH*128*21);
    }
    
    static class MyKeysFetchingLocally implements KeysFetchingLocally {
        private final HashSet<Key> keys = new HashSet<Key>();
        private final HashSet<SendableRequestItemKey> inserts = new HashSet<SendableRequestItemKey>();

        @Override
        public long checkRecentlyFailed(Key key, boolean realTime) {
            return 0;
        }

        public void addInsert(SendableRequestItemKey chosen) {
            inserts.add(chosen);
        }

        @Override
        public boolean hasKey(Key key, BaseSendableGet getterWaiting) {
            return keys.contains(key);
        }

        @Override
        public boolean hasInsert(SendableInsert insert, SendableRequestItemKey token) {
            assertTrue(insert == null);
            return inserts.contains(token);
        }

        public void add(Key k) {
            keys.add(k);
        }

        public void clear() {
            keys.clear();
            inserts.clear();
        }
        
    }
    
    private void testRoundTripSimpleRandom(long size) throws IOException, InsertException, MissingKeyException, FetchException, MetadataParseException, Exception {
        RandomSource r = new DummyRandomSource(12123);
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        Bucket dataBucket = new RAFBucket(data);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.earlyEncode = true;
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertTrue(storage.getStatus() == Status.ENCODED);
        // Encoded. Now try to decode it ...
        cb.waitForHasKeys();
        Metadata metadata = storage.encodeMetadata();

        // Ugly hack because Metadata behaves oddly.
        // FIXME make Metadata behave consistently and get rid.
        Bucket metaBucket = metadata.toBucket(smallBucketFactory);
        Metadata m1 = Metadata.construct(metaBucket);
        Bucket copyBucket = m1.toBucket(smallBucketFactory);
        assertTrue(BucketTools.equalBuckets(metaBucket, copyBucket));
        
        MyFetchCallback fcb = new MyFetchCallback();
        
        FetchContext fctx = HighLevelSimpleClientImpl.makeDefaultFetchContext(size*2, size*2, smallBucketFactory, new SimpleEventProducer());
        
        short cmode = (short) context.getCompatibilityMode().ordinal();
        
        SplitFileFetcherStorage fetcherStorage = new SplitFileFetcherStorage(m1, fcb, new ArrayList<COMPRESSOR_TYPE>(),
                new ClientMetadata(), false, cmode, fctx, false, salt, URI, URI, true, new byte[0],
                r, smallBucketFactory, smallRAFFactory, jobRunner, ticker, memoryLimitedJobRunner, 
                checker, false, null, null, keys);
        
        fetcherStorage.start(false);
        
        // Fully decode one segment at a time, ignore cross-segment.
        
        for(int i=0;i<storage.segments.length;i++) {
            SplitFileFetcherSegmentStorage fetcherSegment = fetcherStorage.segments[i];
            SplitFileInserterSegmentStorage inserterSegment = storage.segments[i];
            int minBlocks = inserterSegment.dataBlockCount + inserterSegment.crossCheckBlockCount;
            int totalBlocks = inserterSegment.totalBlockCount;
            boolean[] fetched = new boolean[totalBlocks];
            for(int j=0;j<minBlocks;j++) {
                int blockNo;
                do {
                    blockNo = r.nextInt(totalBlocks);
                } while (fetched[blockNo]);
                fetched[blockNo] = true;
                ClientCHKBlock block = inserterSegment.encodeBlock(blockNo);
                assertFalse(fetcherSegment.hasStartedDecode());
                boolean success = fetcherSegment.onGotKey(block.getClientKey().getNodeCHK(), block.getBlock());
                assertTrue(success);
                fcb.checkFailed();
            }
            assertTrue(fetcherSegment.hasStartedDecode());
            fcb.checkFailed();
            waitForDecode(fetcherSegment);
        }
        fcb.waitForFinished();
        verifyOutput(fetcherStorage, dataBucket);
        fetcherStorage.finishedFetcher();
        fcb.waitForFree();
    }
    
    private void testRoundTripCrossSegmentRandom(long size) throws IOException, InsertException, MissingKeyException, FetchException, MetadataParseException, Exception {
        RandomSource r = new DummyRandomSource(12123);
        LockableRandomAccessThing data = generateData(r, size, smallRAFFactory);
        Bucket dataBucket = new RAFBucket(data);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.earlyEncode = true;
        KeysFetchingLocally keysFetching = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keysFetching, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        // Encoded. Now try to decode it ...
        cb.waitForHasKeys();
        Metadata metadata = storage.encodeMetadata();
        assertTrue(storage.getStatus() == Status.ENCODED);

        // Ugly hack because Metadata behaves oddly.
        // FIXME make Metadata behave consistently and get rid.
        Bucket metaBucket = metadata.toBucket(smallBucketFactory);
        Metadata m1 = Metadata.construct(metaBucket);
        Bucket copyBucket = m1.toBucket(smallBucketFactory);
        assertTrue(BucketTools.equalBuckets(metaBucket, copyBucket));
        
        MyFetchCallback fcb = new MyFetchCallback();
        
        FetchContext fctx = HighLevelSimpleClientImpl.makeDefaultFetchContext(size*2, size*2, smallBucketFactory, new SimpleEventProducer());
        
        short cmode = (short) context.getCompatibilityMode().ordinal();
        
        SplitFileFetcherStorage fetcherStorage = new SplitFileFetcherStorage(m1, fcb, new ArrayList<COMPRESSOR_TYPE>(),
                new ClientMetadata(), false, cmode, fctx, false, salt, URI, URI, true, new byte[0],
                r, smallBucketFactory, smallRAFFactory, jobRunner, ticker, memoryLimitedJobRunner, 
                checker, false, null, null, keysFetching);
        
        fetcherStorage.start(false);
        
        int segments = storage.segments.length;
        for(int i=0;i<segments;i++) {
            assertEquals(storage.crossSegments[i].dataBlockCount, fetcherStorage.crossSegments[i].dataBlockCount);
            assertTrue(Arrays.equals(storage.crossSegments[i].getSegmentNumbers(), fetcherStorage.crossSegments[i].getSegmentNumbers()));
            assertTrue(Arrays.equals(storage.crossSegments[i].getBlockNumbers(), fetcherStorage.crossSegments[i].getBlockNumbers()));
        }
        
        // Cross-segment decode.
        // We want to ensure it completes in exactly the number of blocks expected, but we need to
        // ensure at each step that the block hasn't already been decoded.
        
        int requiredBlocks = fcb.getRequiredBlocks();
        
        assertEquals((size + CHKBlock.DATA_LENGTH-1)/CHKBlock.DATA_LENGTH, requiredBlocks);
        
        int i=0;
        while(true) {
            executor.waitForIdle(); // Wait for no encodes/decodes running.
            if(!addRandomBlock(storage, fetcherStorage, r)) break;
            fcb.checkFailed();
            i++;
        }

        // Cross-check doesn't necessarily complete in exactly the number of required blocks.
        assertTrue(i >= requiredBlocks);
        assertTrue(i < requiredBlocks + storage.totalCrossCheckBlocks());
        assertTrue(i < requiredBlocks + storage.totalCrossCheckBlocks() - 1); // Implies at least one cross-segment block decoded.
        executor.waitForIdle(); // Wait for no encodes/decodes running.
        fcb.waitForFinished();
        verifyOutput(fetcherStorage, dataBucket);
        fetcherStorage.finishedFetcher();
        fcb.waitForFree();
    }
    
    /** Add a random block that has not been added already or decoded already. 
     * @throws IOException */
    private boolean addRandomBlock(SplitFileInserterStorage storage,
            SplitFileFetcherStorage fetcherStorage, Random random) throws IOException {
        int segCount = storage.segments.length;
        boolean[] exhaustedSegments = new boolean[segCount];
        for(int i=0;i<segCount;i++) {
            while(true) {
                int segNo = random.nextInt(segCount);
                if(exhaustedSegments[segNo]) continue;
                SplitFileFetcherSegmentStorage segment = fetcherStorage.segments[segNo];
                if(segment.isDecodingOrFinished()) {
                    exhaustedSegments[segNo] = true;
                    break;
                }
                while(true) {
                    int blockNo = random.nextInt(segment.totalBlocks());
                    if(segment.hasBlock(blockNo)) {
                        continue;
                    }
                    ClientCHKBlock block = storage.segments[segNo].encodeBlock(blockNo);
                    boolean success = segment.onGotKey(block.getClientKey().getNodeCHK(), block.getBlock());
                    assertTrue(success);
                    return true;
                }
            }
        }
        return false;
    }

    private void verifyOutput(SplitFileFetcherStorage storage, Bucket originalData) throws IOException {
        StreamGenerator g = storage.streamGenerator();
        Bucket out = smallBucketFactory.makeBucket(-1);
        OutputStream os = out.getOutputStream();
        g.writeTo(os, null);
        os.close();
        assertTrue(BucketTools.equalBuckets(originalData, out));
        out.free();
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
    
    class MyFetchCallback implements SplitFileFetcherStorageCallback {
        
        
        private boolean succeeded;
        private boolean failed;
        private int queuedHealing;
        private boolean closed;
        private int requiredBlocks;
        private int totalBlocks;
        private int fetchedBlocks;
        private int failedBlocks;
        private Exception failedException;
        private int restartedAfterDataCorruption;

        @Override
        public synchronized void onSuccess() {
            succeeded = true;
            notifyAll();
        }

        public synchronized int getRequiredBlocks() {
            return requiredBlocks;
        }

        public synchronized void waitForFree() throws Exception {
            while(true) {
                checkFailed();
                if(closed) return;
                wait();
            }
        }

        public synchronized void waitForFinished() throws Exception {
            while(true) {
                checkFailed();
                if(succeeded) return;
                wait();
            }
        }

        public synchronized void checkFailed() throws Exception {
            if(!failed) return;
            if(failedException != null) throw failedException;
            assertFalse(true);
        }

        @Override
        public short getPriorityClass() {
            return 0;
        }

        @Override
        public synchronized void failOnDiskError(IOException e) {
            System.err.println(e);
            e.printStackTrace();
            failed = true;
            failedException = e;
            notifyAll();
        }

        @Override
        public void failOnDiskError(ChecksumFailedException e) {
            System.err.println(e);
            e.printStackTrace();
            failed = true;
            failedException = e;
            notifyAll();
        }

        @Override
        public synchronized void setSplitfileBlocks(int requiredBlocks, int remainingBlocks) {
            this.requiredBlocks = requiredBlocks;
            this.totalBlocks = requiredBlocks + remainingBlocks;
            // Ignore
        }

        @Override
        public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max,
                byte[] customSplitfileKey, boolean compressed, boolean bottomLayer,
                boolean definitiveAnyway) {
            // Ignore.
        }

        @Override
        public synchronized void queueHeal(byte[] data, byte[] cryptoKey, byte cryptoAlgorithm) {
            queuedHealing++;
        }

        @Override
        public synchronized void onClosed() {
            closed = true;
            notifyAll();
        }

        @Override
        public synchronized void onFetchedBlock() {
            fetchedBlocks++;
        }

        @Override
        public synchronized void onFailedBlock() {
            failedBlocks++;
        }

        @Override
        public void onResume(int succeededBlocks, int failedBlocks, ClientMetadata mimeType,
                long finalSize) {
            // Ignore.
        }

        @Override
        public synchronized void fail(FetchException e) {
            System.err.println(e);
            e.printStackTrace();
            failed = true;
            failedException = e;
            notifyAll();
        }

        @Override
        public void maybeAddToBinaryBlob(ClientCHKBlock decodedBlock) {
            // Ignore.
        }

        @Override
        public boolean wantBinaryBlob() {
            return false;
        }

        @Override
        public BaseSendableGet getSendableGet() {
            return null;
        }

        @Override
        public synchronized void restartedAfterDataCorruption() {
            restartedAfterDataCorruption++;
        }

        @Override
        public void clearCooldown() {
            // Ignore.
        }

        @Override
        public HasKeyListener getHasKeyListener() {
            return null;
        }

        @Override
        public KeySalter getSalter() {
            return salt;
        }
        
    }

    public void testCancel() throws IOException, InsertException, MissingKeyException {
        Random r = new Random(12124);
        long size = 32768*6;
        BarrierRandomAccessThing data = new BarrierRandomAccessThing(generateData(r, size, smallRAFFactory));
        HashResult[] hashes = getHashes(data);
        data.pause();
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.earlyEncode = true;
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, false, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        assertEquals(storage.getStatus(), Status.STARTED);
        assertEquals(storage.segments.length, 1);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        segment.onFailure(0, new InsertException(InsertException.INTERNAL_ERROR));
        data.proceed(); // Now it will complete encoding, and then report in, and then fail.
        try {
            cb.waitForFinishedEncode();
            assertFalse(true); // Should have failed.
        } catch (InsertException e) {
            assertTrue(executor.isIdle());
            assertFalse(segment.isEncoding());
            assertEquals(storage.getStatus(), Status.FAILED);
        }
    }
    
    public void testPersistentSmallSplitfileNoLastBlockCompletion() throws IOException, InsertException, StorageFormatException, ChecksumFailedException, ResumeFailedException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, bigRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, true, baseContext.clone(), 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        assertEquals(storage.segments[0].dataBlockCount, 2);
        assertEquals(storage.segments[0].checkBlockCount, 3);
        assertEquals(storage.segments[0].crossCheckBlockCount, 0);
        assertTrue(storage.getStatus() == Status.ENCODED);
        executor.waitForIdle();
        SplitFileInserterStorage resumed = new SplitFileInserterStorage(storage.getRAF(), data, cb, r, 
                memoryLimitedJobRunner, jobRunner, ticker, keys, fg, persistentFileTracker);
        assertEquals(resumed.segments.length, 1);
        SplitFileInserterSegmentStorage segment = resumed.segments[0];
        assertEquals(segment.dataBlockCount, 2);
        assertEquals(segment.checkBlockCount, 3);
        assertEquals(segment.crossCheckBlockCount, 0);
        assertTrue(resumed.getStatus() == Status.ENCODED);
        for(int i=0;i<segment.totalBlockCount;i++) {
            segment.onInsertedBlock(i, segment.encodeBlock(i).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, resumed.getStatus());
    }

    public void testPersistentSmallSplitfileNoLastBlockCompletionAfterResume() throws IOException, InsertException, StorageFormatException, ChecksumFailedException, ResumeFailedException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, bigRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, true, baseContext.clone(), 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        assertEquals(storage.segments[0].dataBlockCount, 2);
        assertEquals(storage.segments[0].checkBlockCount, 3);
        assertEquals(storage.segments[0].crossCheckBlockCount, 0);
        assertTrue(storage.getStatus() == Status.ENCODED);
        SplitFileInserterStorage resumed = null;
        for(int i=0;i<storage.segments[0].totalBlockCount;i++) {
            executor.waitForIdle();
            resumed = new SplitFileInserterStorage(storage.getRAF(), data, cb, r, 
                    memoryLimitedJobRunner, jobRunner, ticker, keys, fg, persistentFileTracker);
            assertEquals(resumed.segments.length, 1);
            SplitFileInserterSegmentStorage segment = resumed.segments[0];
            assertEquals(segment.dataBlockCount, 2);
            assertEquals(segment.checkBlockCount, 3);
            assertEquals(segment.crossCheckBlockCount, 0);
            assertTrue(resumed.getStatus() == Status.ENCODED);
            segment.onInsertedBlock(i, segment.encodeBlock(i).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, resumed.getStatus());
    }
    
    public void testPersistentSmallSplitfileWithLastBlockCompletionAfterResume() throws IOException, InsertException, StorageFormatException, ChecksumFailedException, ResumeFailedException {
        Random r = new Random(12121);
        long size = 65535; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, bigRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, true, baseContext.clone(), 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        assertEquals(storage.segments[0].dataBlockCount, 2);
        assertEquals(storage.segments[0].checkBlockCount, 3);
        assertEquals(storage.segments[0].crossCheckBlockCount, 0);
        assertTrue(storage.getStatus() == Status.ENCODED);
        SplitFileInserterStorage resumed = null;
        for(int i=0;i<storage.segments[0].totalBlockCount;i++) {
            executor.waitForIdle();
            resumed = new SplitFileInserterStorage(storage.getRAF(), data, cb, r, 
                    memoryLimitedJobRunner, jobRunner, ticker, keys, fg, persistentFileTracker);
            assertEquals(resumed.segments.length, 1);
            SplitFileInserterSegmentStorage segment = resumed.segments[0];
            assertEquals(segment.dataBlockCount, 2);
            assertEquals(segment.checkBlockCount, 3);
            assertEquals(segment.crossCheckBlockCount, 0);
            assertTrue(resumed.getStatus() == Status.ENCODED);
            segment.onInsertedBlock(i, segment.encodeBlock(i).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, resumed.getStatus());
    }
    
    public void testPersistentSmallSplitfileNoLastBlockFailAfterResume() throws IOException, InsertException, StorageFormatException, ChecksumFailedException, ResumeFailedException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, bigRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        InsertContext context = baseContext.clone();
        context.consecutiveRNFsCountAsSuccess = 0;
        context.maxInsertRetries = 2;
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, true, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        assertEquals(storage.segments[0].dataBlockCount, 2);
        assertEquals(storage.segments[0].checkBlockCount, 3);
        assertEquals(storage.segments[0].crossCheckBlockCount, 0);
        assertTrue(storage.getStatus() == Status.ENCODED);
        SplitFileInserterStorage resumed = null;
        
        for(int i=0;i<3;i++) {
            executor.waitForIdle();
            resumed = new SplitFileInserterStorage(storage.getRAF(), data, cb, r, 
                    memoryLimitedJobRunner, jobRunner, ticker, keys, fg, persistentFileTracker);
            assertEquals(resumed.segments.length, 1);
            SplitFileInserterSegmentStorage segment = resumed.segments[0];
            assertEquals(segment.dataBlockCount, 2);
            assertEquals(segment.checkBlockCount, 3);
            assertEquals(segment.crossCheckBlockCount, 0);
            assertTrue(resumed.getStatus() == Status.ENCODED);
            segment.onFailure(0, new InsertException(InsertException.ROUTE_NOT_FOUND));
        }
        try {
            cb.waitForSucceededInsert();
            assertTrue(false);
        } catch (InsertException e) {
            assertEquals(e.mode, InsertException.TOO_MANY_RETRIES_IN_BLOCKS);
            assertTrue(e.errorCodes != null);
            assertEquals(3, e.errorCodes.getErrorCount(InsertException.ROUTE_NOT_FOUND));
            assertEquals(e.errorCodes.totalCount(), 3);
            assertEquals(Status.FAILED, resumed.getStatus());
        }
        assertEquals(Status.FAILED, resumed.getStatus());
    }

    public void testPersistentSmallSplitfileNoLastBlockChooseAfterResume() throws IOException, InsertException, StorageFormatException, ChecksumFailedException, ResumeFailedException {
        Random r = new Random(12121);
        long size = 65536; // Exact multiple, so no last block
        LockableRandomAccessThing data = generateData(r, size, bigRAFFactory);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        MyKeysFetchingLocally keys = new MyKeysFetchingLocally();
        InsertContext context = baseContext.clone();
        context.consecutiveRNFsCountAsSuccess = 0;
        context.maxInsertRetries = 1;
        SplitFileInserterStorage storage = new SplitFileInserterStorage(data, size, cb, null,
                new ClientMetadata(), false, null, smallRAFFactory, true, context, 
                cryptoAlgorithm, cryptoKey, null, hashes, smallBucketFactory, checker, 
                r, memoryLimitedJobRunner, jobRunner, ticker, keys, false, 0, 0, 0, 0);
        storage.start();
        cb.waitForFinishedEncode();
        assertEquals(storage.segments.length, 1);
        assertEquals(storage.segments[0].dataBlockCount, 2);
        assertEquals(storage.segments[0].checkBlockCount, 3);
        assertEquals(storage.segments[0].crossCheckBlockCount, 0);
        assertTrue(storage.getStatus() == Status.ENCODED);
        SplitFileInserterStorage resumed = null;
        int totalBlockCount = storage.segments[0].totalBlockCount;
        
        boolean[] chosenBlocks = new boolean[totalBlockCount];
        // Choose and fail all blocks.
        for(int i=0;i<totalBlockCount;i++) {
            executor.waitForIdle();
            resumed = new SplitFileInserterStorage(storage.getRAF(), data, cb, r, 
                    memoryLimitedJobRunner, jobRunner, ticker, keys, fg, persistentFileTracker);
            assertEquals(resumed.segments.length, 1);
            SplitFileInserterSegmentStorage segment = resumed.segments[0];
            assertEquals(segment.dataBlockCount, 2);
            assertEquals(segment.checkBlockCount, 3);
            assertEquals(segment.crossCheckBlockCount, 0);
            assertTrue(resumed.getStatus() == Status.ENCODED);

            BlockInsert chosen = segment.chooseBlock();
            assertTrue(chosen != null);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onFailure(chosen.blockNumber, new InsertException(InsertException.ROUTE_NOT_FOUND));
        }
        keys.clear();
        // Choose and succeed all blocks.
        chosenBlocks = new boolean[totalBlockCount];
        for(int i=0;i<totalBlockCount;i++) {
            executor.waitForIdle();
            resumed = new SplitFileInserterStorage(storage.getRAF(), data, cb, r, 
                    memoryLimitedJobRunner, jobRunner, ticker, keys, fg, persistentFileTracker);
            assertEquals(resumed.segments.length, 1);
            SplitFileInserterSegmentStorage segment = resumed.segments[0];
            assertEquals(segment.dataBlockCount, 2);
            assertEquals(segment.checkBlockCount, 3);
            assertEquals(segment.crossCheckBlockCount, 0);
            assertTrue(resumed.getStatus() == Status.ENCODED);

            BlockInsert chosen = segment.chooseBlock();
            keys.addInsert(chosen);
            assertTrue(chosen != null);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onInsertedBlock(chosen.blockNumber, segment.encodeBlock(chosen.blockNumber).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(resumed.getStatus(), Status.SUCCEEDED);
    }

}
