package freenet.client.async;

import static org.junit.Assert.*;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

import freenet.support.io.*;
import org.junit.Assert;
import org.junit.Test;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClientImpl;
import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.InsertException;
import freenet.client.InsertException.InsertExceptionMode;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.async.SplitFileInserterSegmentStorage.BlockInsert;
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
import freenet.support.api.LockableRandomAccessBuffer;
import freenet.support.api.LockableRandomAccessBufferFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

public class SplitFileInserterStorageTest {

    final LockableRandomAccessBufferFactory smallRAFFactory = new ByteArrayRandomAccessBufferFactory();
    final FilenameGenerator fg;
    final PersistentFileTracker persistentFileTracker;
    final LockableRandomAccessBufferFactory bigRAFFactory;
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
    final KeySalter salt = Key::getRoutingKey;
    private final FreenetURI URI;
    private Random random;
    private long size;
    private MyCallback cb;
    private LockableRandomAccessBuffer data;
    private HashResult[] hashes;
    private MyKeysFetchingLocally keys;
    private InsertContext context;

    public SplitFileInserterStorageTest() throws IOException {
        dir = new File("split-file-inserter-storage-test");
        dir.mkdir();
        executor = new WaitableExecutor(new PooledExecutor());
        ticker = new CheatingTicker(executor);
        RandomSource r = new DummyRandomSource(12345);
        fg = new FilenameGenerator(r, true, dir, "freenet-test");
        persistentFileTracker = new TrivialPersistentFileTracker(dir, fg);
        bigRAFFactory = new PooledFileRandomAccessBufferFactory(fg, r);
        smallBucketFactory = new ArrayBucketFactory();
        bigBucketFactory = new TempBucketFactory(executor, fg, 0, 0, r, false, 0, null);
        baseContext = HighLevelSimpleClientImpl.makeDefaultInsertContext(bigBucketFactory, new SimpleEventProducer());
        cryptoKey = new byte[32];
        r.nextBytes(cryptoKey);
        checker = new CRCChecksumChecker();
        memoryLimitedJobRunner = new MemoryLimitedJobRunner(9 * 1024 * 1024L, 20, executor, NativeThread.JAVA_PRIORITY_RANGE);
        jobRunner = new DummyJobRunner(executor, null);
        URI = FreenetURI.generateRandomCHK(r);
        random = new Random(12121);
        size = 65536; // Exact multiple, so no last block
        cb = new MyCallback();
        data = generateData(random, size);
        hashes = getHashes(data);
        keys = new MyKeysFetchingLocally();
        context = baseContext.clone();
    }

    private static class MyCallback implements SplitFileInserterStorageCallback {

        private boolean finishedEncode;
        private boolean hasKeys;
        private boolean succeededInsert;
        private InsertException failed;

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
            if (failed != null) {
                throw failed;
            }
        }

        public synchronized void waitForFinishedEncode() throws InsertException {
            while (!finishedEncode) {
                checkFailed();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }

        public synchronized void waitForHasKeys() throws InsertException {
            while (!hasKeys) {
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
            notifyAll();
        }

        public synchronized void waitForSucceededInsert() throws InsertException {
            while (!succeededInsert) {
                checkFailed();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }

        @Override
        public synchronized void onFailed(InsertException e) {
            failed = e;
            notifyAll();
        }

        @Override
        public void onInsertedBlock() {
            // Ignore.
        }

        @Override
        public void clearCooldown() {
            // Ignore.
        }

        public synchronized boolean hasFailed() {
            return failed != null;
        }

        public synchronized boolean hasFinishedEncode() {
            return finishedEncode;
        }

        @Override
        public short getPriorityClass() {
            return 0;
        }

    }

    @Test
    public void testSmallSplitfileNoLastBlock() throws Exception {
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, baseContext.clone(), cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        assertProperSegmentStateAndGet(storage);
    }

    @Test
    public void testSmallSplitfileWithLastBlock() throws Exception {
        byte[] originalData = new byte[(int) size];
        random.nextBytes(originalData);
        data = smallRAFFactory.makeRAF(originalData, 0, originalData.length, true);
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, baseContext.clone(), cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        // Now check the data blocks...
        assertProperSegmentStateAndGet(storage);
        assertArrayEquals(
            storage.readSegmentDataBlock(0, 0),
            Arrays.copyOfRange(originalData, 0, CHKBlock.DATA_LENGTH)
        );
        int truncateLength = (int) (size % CHKBlock.DATA_LENGTH);
        long offsetLastBlock = size - truncateLength;
        byte[] buf = storage.readSegmentDataBlock(0, 1);
        assertEquals(CHKBlock.DATA_LENGTH, buf.length);
        byte[] truncated = Arrays.copyOfRange(buf, 0, truncateLength);
        byte[] originalLastBlock = Arrays.copyOfRange(originalData, (int) offsetLastBlock, originalData.length);
        assertEquals(originalLastBlock.length, truncated.length);
        assertArrayEquals(originalLastBlock, truncated);
        assertEquals(Status.ENCODED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileHasKeys() throws Exception {
        context.earlyEncode = true;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        assertProperSegmentStateAndGet(storage);
        cb.waitForHasKeys();
        for (int i = 0; i < storage.segments[0].dataBlockCount + storage.segments[0].checkBlockCount + storage.segments[0].crossCheckBlockCount; i++) {
            storage.segments[0].readKey(i);
        }
        storage.encodeMetadata();
        assertEquals(Status.ENCODED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileCompletion() throws Exception {
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(storage);
        for (int i = 0; i < segment.totalBlockCount; i++) {
            segment.onInsertedBlock(i, segment.encodeBlock(i).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileChooseCompletion() throws Exception {
        context.maxInsertRetries = 2;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(storage);
        boolean[] chosenBlocks = new boolean[segment.totalBlockCount];
        // Choose and fail all blocks.
        for (int i = 0; i < segment.totalBlockCount; i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertNotNull(chosen);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onFailure(chosen.blockNumber, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
        }
        keys.clear();
        // Choose and succeed all blocks.
        chosenBlocks = new boolean[segment.totalBlockCount];
        for (int i = 0; i < segment.totalBlockCount; i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertNotNull(chosen);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onInsertedBlock(chosen.blockNumber, segment.encodeBlock(chosen.blockNumber).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileChooseCooldown() throws Exception {
        context.maxInsertRetries = 2;
        context.consecutiveRNFsCountAsSuccess = 2;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(storage);
        boolean[] chosenBlocks = new boolean[segment.totalBlockCount];
        assertFalse(storage.noBlocksToSend());
        // Choose and fail all blocks.
        for (int i = 0; i < segment.totalBlockCount; i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertNotNull(chosen);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
        }
        assertNull(storage.chooseBlock());
        assertTrue(storage.noBlocksToSend());
        for (int i = 0; i < segment.totalBlockCount; i++) {
            segment.onFailure(i, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
            assertFalse(storage.noBlocksToSend());
        }
        keys.clear();
        // Choose and succeed all blocks.
        chosenBlocks = new boolean[segment.totalBlockCount];
        for (int i = 0; i < segment.totalBlockCount; i++) {
            BlockInsert chosen = segment.chooseBlock();
            keys.addInsert(chosen);
            assertNotNull(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onInsertedBlock(chosen.blockNumber, segment.encodeBlock(chosen.blockNumber).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileChooseCooldownNotRNF() throws Exception {
        context.maxInsertRetries = 2;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(storage);
        boolean[] chosenBlocks = new boolean[segment.totalBlockCount];
        assertFalse(storage.noBlocksToSend());
        // Choose and fail all blocks.
        for (int i = 0; i < segment.totalBlockCount; i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertNotNull(chosen);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
        }
        assertNull(storage.chooseBlock());
        assertTrue(storage.noBlocksToSend());
        for (int i = 0; i < segment.totalBlockCount; i++) {
            // We need to test this path too.
            segment.onFailure(i, new InsertException(InsertExceptionMode.REJECTED_OVERLOAD));
            assertFalse(storage.noBlocksToSend());
        }
        keys.clear();
        // Choose and succeed all blocks.
        chosenBlocks = new boolean[segment.totalBlockCount];
        for (int i = 0; i < segment.totalBlockCount; i++) {
            BlockInsert chosen = segment.chooseBlock();
            keys.addInsert(chosen);
            assertNotNull(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onInsertedBlock(chosen.blockNumber, segment.encodeBlock(chosen.blockNumber).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileConsecutiveRNFsHack() throws Exception {
        context.maxInsertRetries = 0;
        context.consecutiveRNFsCountAsSuccess = 2;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(storage);
        boolean[] chosenBlocks = new boolean[segment.totalBlockCount];
        // First RNF.
        for (int i = 0; i < segment.totalBlockCount; i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertNotNull(chosen);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.setKey(chosen.blockNumber, segment.encodeBlock(chosen.blockNumber).getClientKey());
            segment.onFailure(chosen.blockNumber, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
        }
        chosenBlocks = new boolean[segment.totalBlockCount];
        // Second RNF.
        keys.clear();
        for (int i = 0; i < segment.totalBlockCount; i++) {
            BlockInsert chosen = segment.chooseBlock();
            assertNotNull(chosen);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onFailure(chosen.blockNumber, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
        }
        // Should count as success at this point.
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileConsecutiveRNFsHackFailure() throws Exception {
        // Do 2 RNFs and then a RejectedOverload. Should fail at that point.
        context.maxInsertRetries = 2;
        context.consecutiveRNFsCountAsSuccess = 3;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(storage);
        segment.setKey(0, segment.encodeBlock(0).getClientKey());
        segment.onFailure(0, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
        assertEquals(Status.ENCODED, storage.getStatus());
        segment.onFailure(0, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
        assertEquals(Status.ENCODED, storage.getStatus());
        segment.onFailure(0, new InsertException(InsertExceptionMode.REJECTED_OVERLOAD));
        // Should count as success at this point.
        InsertException e = assertThrows(InsertException.class, cb::waitForSucceededInsert);
        assertEquals(InsertExceptionMode.TOO_MANY_RETRIES_IN_BLOCKS, e.mode);
        assertNotNull(e.errorCodes);
        assertEquals(2, e.errorCodes.getErrorCount(InsertExceptionMode.ROUTE_NOT_FOUND));
        assertEquals(1, e.errorCodes.getErrorCount(InsertExceptionMode.REJECTED_OVERLOAD));
        assertEquals(3, e.errorCodes.totalCount());
        assertEquals(Status.FAILED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileFailureMaxRetries() throws Exception {
        context.consecutiveRNFsCountAsSuccess = 0;
        context.maxInsertRetries = 2;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(storage);
        for (int i = 0; i < 3; i++) {
            segment.onFailure(0, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
        }

        InsertException e = assertThrows(InsertException.class, () -> cb.waitForSucceededInsert());

        assertEquals(InsertExceptionMode.TOO_MANY_RETRIES_IN_BLOCKS, e.mode);
        assertNotNull(e.errorCodes);
        assertEquals(3, e.errorCodes.getErrorCount(InsertExceptionMode.ROUTE_NOT_FOUND));
        assertEquals(3, e.errorCodes.totalCount());
        assertEquals(Status.FAILED, storage.getStatus());
    }

    @Test
    public void testSmallSplitfileFailureFatalError() throws Exception {
        context.maxInsertRetries = 2;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(storage);
        assertTrue(InsertException.isFatal(InsertExceptionMode.INTERNAL_ERROR));
        segment.onFailure(0, new InsertException(InsertExceptionMode.INTERNAL_ERROR));

        InsertException e = assertThrows(InsertException.class, () -> cb.waitForSucceededInsert());
        assertEquals(InsertExceptionMode.FATAL_ERRORS_IN_BLOCKS, e.mode);
        assertNotNull(e.errorCodes);
        assertEquals(1, e.errorCodes.getErrorCount(InsertExceptionMode.INTERNAL_ERROR));
        assertEquals(1, e.errorCodes.totalCount());
        assertEquals(Status.FAILED, storage.getStatus());
    }

    private HashResult[] getHashes(LockableRandomAccessBuffer data) throws IOException {
        try (
            InputStream is = new RAFInputStream(data, 0, data.size());
            MultiHashInputStream hashStream = new MultiHashInputStream(is, HashType.SHA256.bitmask)
        ) {
            FileUtil.copy(is, new NullOutputStream(), data.size());
            return hashStream.getResults();
        }
    }

    private LockableRandomAccessBuffer generateData(Random random, long size) throws IOException {
        // Use small factory for anything <= 256KiB
        LockableRandomAccessBufferFactory f = size > 262144 ? bigRAFFactory : smallRAFFactory;
        return generateData(random, size, f);
    }

    private LockableRandomAccessBuffer generateData(Random random, long size,
                                                    LockableRandomAccessBufferFactory factory) throws IOException {
        LockableRandomAccessBuffer thing = factory.makeRAF(size);
        BucketTools.fill(thing, random, 0, size);
        return new ReadOnlyRandomAccessBuffer(thing);
    }

    @Test
    public void testRoundTripSimple() throws Exception {
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH * 2, CompatibilityMode.COMPAT_CURRENT);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH * 2 - 1, CompatibilityMode.COMPAT_CURRENT);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH * 128, CompatibilityMode.COMPAT_CURRENT);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH * 128 + 1, CompatibilityMode.COMPAT_CURRENT);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH * 192, CompatibilityMode.COMPAT_CURRENT);
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH * 192 + 1, CompatibilityMode.COMPAT_CURRENT);
    }

    @Test
    public void testRoundTripOneBlockSegment() throws Exception {
        testRoundTripSimpleRandom(CHKBlock.DATA_LENGTH * (128 + 1) - 1, CompatibilityMode.COMPAT_1250_EXACT);
    }

    @Test
    public void testRoundTripCrossSegment() throws Exception {
        if (!TestProperty.EXTENSIVE) return;
        // Test cross-segment:
        testRoundTripCrossSegmentRandom(CHKBlock.DATA_LENGTH * 128 * 21);
    }

    @Test
    public void testRoundTripDataBlocksOnly() throws Exception {
        testRoundTripCrossSegmentDataBlocks(CHKBlock.DATA_LENGTH * 128 * 5);
        if (!TestProperty.EXTENSIVE) return;
        // Test cross-segment:
        testRoundTripCrossSegmentDataBlocks(CHKBlock.DATA_LENGTH * 128 * 21);
    }

    @Test
    public void testResumeCrossSegment() throws Exception {
        if (!TestProperty.EXTENSIVE) return;
        testResumeCrossSegment(CHKBlock.DATA_LENGTH * 128 * 21);
    }

    @Test
    public void testEncodeAfterShutdownCrossSegment() throws Exception {
        if (!TestProperty.EXTENSIVE) return;
        testEncodeAfterShutdownCrossSegment(CHKBlock.DATA_LENGTH * 128 * 21);
    }

    @Test
    public void testRepeatedEncodeAfterShutdown() throws Exception {
        testRepeatedEncodeAfterShutdownCrossSegment(CHKBlock.DATA_LENGTH * 128 * 5); // Not cross-segment.
        if (!TestProperty.EXTENSIVE) return;
        testRepeatedEncodeAfterShutdownCrossSegment(CHKBlock.DATA_LENGTH * 128 * 21); // Cross-segment.
    }

    private static class MyKeysFetchingLocally implements KeysFetchingLocally {
        private final HashSet<Key> keys = new HashSet<>();
        private final HashSet<SendableRequestItemKey> inserts = new HashSet<>();

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
        public boolean hasInsert(SendableRequestItemKey token) {
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

    private void testRoundTripSimpleRandom(long size, CompatibilityMode cmode) throws Exception {
        RandomSource r = new DummyRandomSource(12123);
        LockableRandomAccessBuffer data = generateData(r, size);
        Bucket dataBucket = new RAFBucket(data);
        HashResult[] hashes = getHashes(data);
        MyCallback cb = new MyCallback();
        InsertContext context = baseContext.clone();
        context.earlyEncode = true;
        context.setCompatibilityMode(cmode);
        cmode = context.getCompatibilityMode();
        KeysFetchingLocally keys = new MyKeysFetchingLocally();
        boolean old = cmode.code < CompatibilityMode.COMPAT_1255.code;
        byte cryptoAlgorithm;
        if (!(cmode == CompatibilityMode.COMPAT_CURRENT || cmode.ordinal() >= CompatibilityMode.COMPAT_1416.ordinal())) {
            cryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256;
        } else {
            cryptoAlgorithm = Key.ALGO_AES_CTR_256_SHA256;
        }
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, old ? null : cryptoKey, hashes, r, memoryLimitedJobRunner, keys);
        assertEquals(Status.ENCODED, storage.getStatus());
        // Encoded. Now try to decode it ...
        cb.waitForHasKeys();
        Metadata metadata = storage.encodeMetadata();

        // Ugly hack because Metadata behaves oddly.
        // FIXME make Metadata behave consistently and get rid.
        Bucket metaBucket = metadata.toBucket(smallBucketFactory);
        Metadata m1 = Metadata.construct(metaBucket);
        Bucket copyBucket = m1.toBucket(smallBucketFactory);
        assertTrue(BucketTools.equalBuckets(metaBucket, copyBucket));

        FetchCallbackForTestingSplitFileInserter fcb = new FetchCallbackForTestingSplitFileInserter();

        FetchContext fctx = HighLevelSimpleClientImpl.makeDefaultFetchContext(size * 2, size * 2, smallBucketFactory, new SimpleEventProducer());

        SplitFileFetcherStorage fetcherStorage = createFetcherStorage(m1, fcb, new ArrayList<>(), cmode.code, fctx, r);

        fetcherStorage.start(false);

        // Fully decode one segment at a time, ignore cross-segment.

        for (int i = 0; i < storage.segments.length; i++) {
            SplitFileFetcherSegmentStorage fetcherSegment = fetcherStorage.segments[i];
            SplitFileInserterSegmentStorage inserterSegment = storage.segments[i];
            int minBlocks = inserterSegment.dataBlockCount + inserterSegment.crossCheckBlockCount;
            int totalBlocks = inserterSegment.totalBlockCount;
            boolean[] fetched = new boolean[totalBlocks];
            if (i == storage.segments.length - 1 && cmode.ordinal() < CompatibilityMode.COMPAT_1255.ordinal()) {
                fetched[inserterSegment.dataBlockCount - 1] = true; // We don't use the last block of the last segment for old splitfiles
            }
            for (int j = 0; j < minBlocks; j++) {
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

    private void testResumeCrossSegment(long size) throws Exception {
        LockableRandomAccessBuffer data = generateData(random, size);
        hashes = getHashes(data);
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, true, baseContext.clone(), cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        cb.waitForHasKeys();
        executor.waitForIdle();
        Metadata metadata = storage.encodeMetadata();
        assertEquals(Status.ENCODED, storage.getStatus());
        Bucket mBucket1 = bigBucketFactory.makeBucket(-1);
        try (DataOutputStream os = new DataOutputStream(mBucket1.getOutputStream())) {
            metadata.writeTo(os);
        }
        SplitFileInserterStorage resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
        // Doesn't need to start since already encoded.
        Metadata metadata2 = storage.encodeMetadata();
        Bucket mBucket2 = bigBucketFactory.makeBucket(-1);
        try (DataOutputStream os = new DataOutputStream(mBucket2.getOutputStream())) {
            metadata2.writeTo(os);
        }
        assertTrue(BucketTools.equalBuckets(mBucket1, mBucket2));
        // Choose and succeed all blocks.
        boolean[][] chosenBlocks = new boolean[storage.segments.length][];
        for (int i = 0; i < storage.segments.length; i++) {
            int blocks = storage.segments[i].totalBlockCount;
            chosenBlocks[i] = new boolean[blocks];
            assertEquals(storage.segments[i].totalBlockCount, resumed.segments[i].totalBlockCount);
        }
        int totalBlocks = storage.getTotalBlockCount();
        assertEquals(totalBlocks, resumed.getTotalBlockCount());
        for (int i = 0; i < totalBlocks; i++) {
            BlockInsert chosen = resumed.chooseBlock();
            if (chosen == null) {
                fail();
            } else {
                keys.addInsert(chosen);
            }
            assertNotNull(chosen);
            assertFalse(chosenBlocks[chosen.segment.segNo][chosen.blockNumber]);
            chosenBlocks[chosen.segment.segNo][chosen.blockNumber] = true;
            chosen.segment.onInsertedBlock(
                chosen.blockNumber,
                chosen.segment.encodeBlock(chosen.blockNumber).getClientKey()
            );
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, resumed.getStatus());
    }

    private void testEncodeAfterShutdownCrossSegment(long size) throws Exception {
        data = generateData(random, size);
        hashes = getHashes(data);
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, true, baseContext.clone(), cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        executor.waitForIdle();
        // Has not encoded anything.
        for (SplitFileInserterSegmentStorage segment : storage.segments) {
            assertFalse(segment.isFinishedEncoding());
        }
        SplitFileInserterStorage resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
        resumed.start();
        cb.waitForFinishedEncode();
        cb.waitForHasKeys();
        executor.waitForIdle();
        resumed.encodeMetadata();
        assertEquals(Status.ENCODED, resumed.getStatus());
        resumed.originalData.free();
        resumed.getRAF().free();
    }

    private void testRepeatedEncodeAfterShutdownCrossSegment(long size) throws Exception {
        data = generateData(random, size);
        hashes = getHashes(data);
        // Only enough for one segment at a time.
        MemoryLimitedJobRunner memoryLimitedJobRunner = new MemoryLimitedJobRunner(9 * 1024 * 1024L, 1, executor, NativeThread.JAVA_PRIORITY_RANGE);
        SplitFileInserterStorage storage = new SplitFileInserterStorage(
            data,
            size,
            cb,
            null,
            new ClientMetadata(),
            false,
            null,
            smallRAFFactory,
            true,
            baseContext.clone(),
            cryptoAlgorithm,
            cryptoKey,
            null,
            hashes,
            smallBucketFactory,
            checker,
            random,
            memoryLimitedJobRunner,
            jobRunner,
            ticker,
            keys,
            false,
            0,
            0,
            0,
            0
        );
        executor.waitForIdle();
        // Has not encoded anything.
        for (SplitFileInserterSegmentStorage segment : storage.segments) {
            assertFalse(segment.isFinishedEncoding());
        }
        SplitFileInserterStorage resumed = null;
        if (storage.crossSegments != null) {
            for (int i = 0; i < storage.crossSegments.length; i++) {
                memoryLimitedJobRunner = new MemoryLimitedJobRunner(9 * 1024 * 1024L, 1, executor, NativeThread.JAVA_PRIORITY_RANGE);
                resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
                assertEquals(i, countEncodedCrossSegments(resumed));
                resumed.start();
                // The memoryLimitedJobRunner will only encode one segment at a time.
                // Wait for it to encode one segment.
                memoryLimitedJobRunner.shutdown();
                memoryLimitedJobRunner.waitForShutdown();
                executor.waitForIdle();
                assertEquals(i + 1, countEncodedCrossSegments(resumed));
            }
        }

        for (int i = 0; i < storage.segments.length; i++) {
            memoryLimitedJobRunner = new MemoryLimitedJobRunner(9 * 1024 * 1024L, 1, executor, NativeThread.JAVA_PRIORITY_RANGE);
            resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
            assertEquals(i, countEncodedSegments(resumed));
            if (storage.crossSegments != null) {
                assertEquals(resumed.crossSegments.length, countEncodedCrossSegments(resumed));
                assertEquals(Status.ENCODED_CROSS_SEGMENTS, resumed.getStatus());
            }
            resumed.start();
            // The memoryLimitedJobRunner will only encode one segment at a time.
            // Wait for it to encode one segment.
            memoryLimitedJobRunner.shutdown();
            memoryLimitedJobRunner.waitForShutdown();
            executor.waitForIdle();
            assertEquals(i + 1, countEncodedSegments(resumed));
        }

        cb.waitForFinishedEncode();
        cb.waitForHasKeys();
        executor.waitForIdle();
        assertNotNull(resumed);
        resumed.encodeMetadata();
        assertEquals(Status.ENCODED, resumed.getStatus());
        resumed.originalData.free();
        resumed.getRAF().free();
    }

    private int countEncodedSegments(SplitFileInserterStorage storage) {
        int total = 0;
        for (SplitFileInserterSegmentStorage segment : storage.segments) {
            if (segment.isFinishedEncoding()) {
                total++;
            }
        }
        return total;
    }

    private int countEncodedCrossSegments(SplitFileInserterStorage storage) {
        int total = 0;
        for (SplitFileInserterCrossSegmentStorage segment : storage.crossSegments) {
            if (segment.isFinishedEncoding()) {
                total++;
            }
        }
        return total;
    }

    private void testRoundTripCrossSegmentRandom(long size) throws Exception {
        RandomSource r = new DummyRandomSource(12123);
        data = generateData(r, size);
        Bucket dataBucket = new RAFBucket(data);
        hashes = getHashes(data);
        context.earlyEncode = true;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, r, memoryLimitedJobRunner, keys);
        // Encoded. Now try to decode it ...
        cb.waitForHasKeys();
        Metadata metadata = storage.encodeMetadata();
        assertEquals(Status.ENCODED, storage.getStatus());

        // Ugly hack because Metadata behaves oddly.
        // FIXME make Metadata behave consistently and get rid.
        Bucket metaBucket = metadata.toBucket(smallBucketFactory);
        Metadata m1 = Metadata.construct(metaBucket);
        Bucket copyBucket = m1.toBucket(smallBucketFactory);
        assertTrue(BucketTools.equalBuckets(metaBucket, copyBucket));

        FetchCallbackForTestingSplitFileInserter fcb = new FetchCallbackForTestingSplitFileInserter();

        FetchContext fctx = HighLevelSimpleClientImpl.makeDefaultFetchContext(size * 2, size * 2, smallBucketFactory, new SimpleEventProducer());

        short cmode = (short) context.getCompatibilityMode().ordinal();

        SplitFileFetcherStorage fetcherStorage = createFetcherStorage(m1, fcb, new ArrayList<>(), cmode, fctx, r);

        fetcherStorage.start(false);

        int segments = storage.segments.length;
        for (int i = 0; i < segments; i++) {
            assertEquals(storage.crossSegments[i].dataBlockCount, fetcherStorage.crossSegments[i].dataBlockCount);
            assertArrayEquals(
                storage.crossSegments[i].getSegmentNumbers(),
                fetcherStorage.crossSegments[i].getSegmentNumbers()
            );
            assertArrayEquals(
                storage.crossSegments[i].getBlockNumbers(),
                fetcherStorage.crossSegments[i].getBlockNumbers()
            );
        }

        // Cross-segment decode.
        // We want to ensure it completes in exactly the number of blocks expected, but we need to
        // ensure at each step that the block hasn't already been decoded.

        int requiredBlocks = fcb.getRequiredBlocks();

        int dataBlocks = (int) ((size + CHKBlock.DATA_LENGTH - 1) / CHKBlock.DATA_LENGTH);

        int expectedBlocks = dataBlocks;
        if (storage.crossSegments != null) {
            expectedBlocks += storage.segments.length * 3;
        }

        assertEquals(expectedBlocks, requiredBlocks);

        int i = 0;
        while (true) {
            executor.waitForIdle(); // Wait for no encodes/decodes running.
            if (!addRandomBlock(storage, fetcherStorage, r)) {
                break;
            }
            fcb.checkFailed();
            i++;
        }

        // Cross-check doesn't necessarily complete in exactly the number of required blocks.
        assertTrue(i >= dataBlocks);
        assertTrue("Downloaded more blocks than data+cross check", i < expectedBlocks);
        assertTrue("No cross-segment blocks decoded", i < expectedBlocks - 1);
        executor.waitForIdle(); // Wait for no encodes/decodes running.
        fcb.waitForFinished();
        verifyOutput(fetcherStorage, dataBucket);
        fetcherStorage.finishedFetcher();
        fcb.waitForFree();
    }

    private void testRoundTripCrossSegmentDataBlocks(long size) throws Exception {
        RandomSource r = new DummyRandomSource(12123);
        LockableRandomAccessBuffer data = generateData(r, size);
        Bucket dataBucket = new RAFBucket(data);
        hashes = getHashes(data);
        context.earlyEncode = true;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, false, context, cryptoAlgorithm, cryptoKey, hashes, r, memoryLimitedJobRunner, keys);
        // Encoded. Now try to decode it ...
        cb.waitForHasKeys();
        Metadata metadata = storage.encodeMetadata();
        assertEquals(Status.ENCODED, storage.getStatus());

        // Ugly hack because Metadata behaves oddly.
        // FIXME make Metadata behave consistently and get rid.
        Bucket metaBucket = metadata.toBucket(smallBucketFactory);
        Metadata m1 = Metadata.construct(metaBucket);
        Bucket copyBucket = m1.toBucket(smallBucketFactory);
        assertTrue(BucketTools.equalBuckets(metaBucket, copyBucket));

        FetchCallbackForTestingSplitFileInserter fcb = new FetchCallbackForTestingSplitFileInserter();

        FetchContext fctx = HighLevelSimpleClientImpl.makeDefaultFetchContext(size * 2, size * 2, smallBucketFactory, new SimpleEventProducer());

        short cmode = (short) context.getCompatibilityMode().ordinal();

        SplitFileFetcherStorage fetcherStorage = createFetcherStorage(m1, fcb, new ArrayList<>(), cmode, fctx, r);

        fetcherStorage.start(false);

        if (storage.crossSegments != null) {
            int segments = storage.segments.length;
            for (int i = 0; i < segments; i++) {
                assertEquals(storage.crossSegments[i].dataBlockCount, fetcherStorage.crossSegments[i].dataBlockCount);
                assertArrayEquals(
                    storage.crossSegments[i].getSegmentNumbers(),
                    fetcherStorage.crossSegments[i].getSegmentNumbers()
                );
                assertArrayEquals(
                    storage.crossSegments[i].getBlockNumbers(),
                    fetcherStorage.crossSegments[i].getBlockNumbers()
                );
            }
        }

        // It should be able to decode from just the data blocks.

        for (int segNo = 0; segNo < storage.segments.length; segNo++) {
            SplitFileInserterSegmentStorage inserterSegment = storage.segments[segNo];
            SplitFileFetcherSegmentStorage fetcherSegment = fetcherStorage.segments[segNo];
            for (int blockNo = 0; blockNo < inserterSegment.dataBlockCount; blockNo++) {
                ClientCHKBlock block = inserterSegment.encodeBlock(blockNo);
                boolean success = fetcherSegment.onGotKey(block.getClientKey().getNodeCHK(), block.getBlock());
                assertTrue(success);
            }
        }

        executor.waitForIdle(); // Wait for no encodes/decodes running.
        fcb.waitForFinished();
        verifyOutput(fetcherStorage, dataBucket);
        fetcherStorage.finishedFetcher();
        fcb.waitForFree();
    }

    /**
     * Add a random block that has not been added already or decoded already.
     *
     * @throws IOException if block addition fails
     */
    private boolean addRandomBlock(SplitFileInserterStorage storage,
                                   SplitFileFetcherStorage fetcherStorage, Random random) throws IOException {
        int segCount = storage.segments.length;
        boolean[] exhaustedSegments = new boolean[segCount];
        for (int i = 0; i < segCount; i++) {
            while (true) {
                int segNo = random.nextInt(segCount);
                if (exhaustedSegments[segNo]) {
                    continue;
                }
                SplitFileFetcherSegmentStorage segment = fetcherStorage.segments[segNo];
                if (segment.isDecodingOrFinished()) {
                    exhaustedSegments[segNo] = true;
                    break;
                }
                while (true) {
                    int blockNo = random.nextInt(segment.totalBlocks());
                    if (segment.hasBlock(blockNo)) {
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
        try (OutputStream os = out.getOutputStream()) {
            g.writeTo(os, null);
        }
        assertTrue(BucketTools.equalBuckets(originalData, out));
        out.free();
    }

    private void waitForDecode(SplitFileFetcherSegmentStorage segment) {
        while (!segment.hasSucceeded()) {
            assertFalse(segment.hasFailed());
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }
    }

    private class FetchCallbackForTestingSplitFileInserter implements SplitFileFetcherStorageCallback {

        private boolean succeeded;
        private boolean failed;
        private boolean closed;
        private int requiredBlocks;
        private Exception failedException;

        @Override
        public synchronized void onSuccess() {
            succeeded = true;
            notifyAll();
        }

        public synchronized int getRequiredBlocks() {
            return requiredBlocks;
        }

        public synchronized void waitForFree() throws Exception {
            while (true) {
                checkFailed();
                if (closed) {
                    return;
                }
                wait();
            }
        }

        public synchronized void waitForFinished() throws Exception {
            while (true) {
                checkFailed();
                if (succeeded) {
                    return;
                }
                wait();
            }
        }

        public synchronized void checkFailed() throws Exception {
            if (!failed) {
                return;
            }
            if (failedException != null) {
                throw failedException;
            }
            Assert.fail();
        }

        @Override
        public short getPriorityClass() {
            return 0;
        }

        @Override
        public synchronized void failOnDiskError(IOException e) {
            System.err.printf("Disk error: %s%n", e.getMessage());
            e.printStackTrace();
            failed = true;
            failedException = e;
            notifyAll();
        }

        @Override
        public void failOnDiskError(ChecksumFailedException e) {
            System.err.printf("Disk error: %s%n", e.getMessage());
            e.printStackTrace();
            failed = true;
            failedException = e;
            notifyAll();
        }

        @Override
        public synchronized void setSplitfileBlocks(int requiredBlocks, int remainingBlocks) {
            this.requiredBlocks = requiredBlocks;
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
        }

        @Override
        public synchronized void onClosed() {
            closed = true;
            notifyAll();
        }

        @Override
        public synchronized void onFetchedBlock() {
        }

        @Override
        public synchronized void onFailedBlock() {
        }

        @Override
        public void onResume(int succeededBlocks, int failedBlocks, ClientMetadata mimeType, long finalSize) {
            // Ignore.
        }

        @Override
        public synchronized void fail(FetchException e) {
            System.err.printf("Operation failed: %s%n", e.getMessage());
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
        }

        @Override
        public void clearCooldown() {
            // Ignore.
        }

        @Override
        public void reduceCooldown(long wakeupTime) {
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

    @Test
    public void testCancel() throws Exception {
        this.size = 32768 * 6;
        BarrierRandomAccessBuffer data = new BarrierRandomAccessBuffer(generateData(random, size));
        HashResult[] hashes = getHashes(data);
        data.pause();
        context.earlyEncode = true;
        SplitFileInserterStorage storage = new SplitFileInserterStorage(
            data,
            size,
            cb,
            null,
            new ClientMetadata(),
            false,
            null,
            smallRAFFactory,
            false,
            context,
            cryptoAlgorithm,
            cryptoKey,
            null,
            hashes,
            smallBucketFactory,
            checker,
            random,
            memoryLimitedJobRunner,
            jobRunner,
            ticker,
            keys,
            false,
            0,
            0,
            0,
            0
        );
        storage.start();
        assertEquals(Status.STARTED, storage.getStatus());
        assertEquals(1, storage.segments.length);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        segment.onFailure(0, new InsertException(InsertExceptionMode.INTERNAL_ERROR));
        data.proceed(); // Now it will complete encoding, and then report in, and then fail.

        assertThrows(InsertException.class, () -> cb.waitForFinishedEncode());
        assertFalse(segment.isEncoding());
        assertEquals(Status.FAILED, storage.getStatus());
    }

    @Test
    public void testCancelAlt() throws Exception {
        // We need to check that onFailed() isn't called until after all the cross segment encode threads have finished.
        testCancelAlt(random, 32768 * 6);
    }

    @Test
    public void testCancelAltCrossSegment() throws Exception {
        // We need to check that onFailed() isn't called until after all the cross segment encode threads have finished.
        testCancelAlt(random, CHKBlock.DATA_LENGTH*128*21);
    }

    private void testCancelAlt(Random r, long size) throws Exception {
        // FIXME tricky to wait for "all threads are in pread()", when # threads != # segments.
        // So just set max threads to 1 (only affects this test).
        memoryLimitedJobRunner.setMaxThreads(1);
        BarrierRandomAccessBuffer data = new BarrierRandomAccessBuffer(generateData(r, size));
        HashResult[] hashes = getHashes(data);
        data.pause();
        InsertContext context = baseContext.clone();
        context.earlyEncode = true;
        SplitFileInserterStorage storage = new SplitFileInserterStorage(
            data,
            size,
            cb,
            null,
            new ClientMetadata(),
            false,
            null,
            smallRAFFactory,
            false,
            context,
            cryptoAlgorithm,
            cryptoKey,
            null,
            hashes,
            smallBucketFactory,
            checker,
            r,
            memoryLimitedJobRunner,
            jobRunner,
            ticker,
            keys,
            false,
            0,
            0,
            0,
            0
        );
        storage.start();
        assertEquals(Status.STARTED, storage.getStatus());
        if (storage.crossSegments != null) {
            assertTrue(allCrossSegmentsEncoding(storage));
        } else {
            assertTrue(allSegmentsEncoding(storage));
        }
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        assertTrue(memoryLimitedJobRunner.getRunningThreads() > 0);
        // Wait for one segment to be in pread().
        data.waitForWaiting();
        segment.onFailure(0, new InsertException(InsertExceptionMode.INTERNAL_ERROR));
        assertFalse(cb.hasFailed()); // Callback must not have been called yet.
        data.proceed(); // Now it will complete encoding, and then report in, and then fail.

        assertThrows(InsertException.class, ()-> cb.waitForFinishedEncode());
        if (storage.segments.length > 2) {
            assertFalse(cb.hasFinishedEncode());
            assertTrue(anySegmentNotEncoded(storage));
        }
        assertEquals(0, memoryLimitedJobRunner.getRunningThreads());
        assertFalse(anySegmentEncoding(storage));
        assertEquals(Status.FAILED, storage.getStatus());
    }

    private boolean allSegmentsEncoding(SplitFileInserterStorage storage) {
        for (SplitFileInserterSegmentStorage segment : storage.segments) {
            if (!segment.isEncoding()) {
                return false;
            }
        }
        return true;
    }

    private boolean allCrossSegmentsEncoding(SplitFileInserterStorage storage) {
        if (storage.crossSegments != null) {
            for (SplitFileInserterCrossSegmentStorage segment : storage.crossSegments) {
                if (!segment.isEncoding()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean anySegmentEncoding(SplitFileInserterStorage storage) {
        for (SplitFileInserterSegmentStorage segment : storage.segments) {
            if (segment.isEncoding()) {
                return true;
            }
        }
        if (storage.crossSegments != null) {
            for (SplitFileInserterCrossSegmentStorage segment : storage.crossSegments) {
                if (segment.isEncoding()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean anySegmentNotEncoded(SplitFileInserterStorage storage) {
        for (SplitFileInserterSegmentStorage segment : storage.segments) {
            if (!segment.hasEncoded()) {
                return true;
            }
        }
        if (storage.crossSegments != null) {
            for (SplitFileInserterCrossSegmentStorage segment : storage.crossSegments) {
                if (!segment.hasEncodedSuccessfully()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void testPersistentSmallSplitfileNoLastBlockCompletion() throws Exception {
        LockableRandomAccessBuffer data = generateData(random, size, bigRAFFactory);
        HashResult[] hashes = getHashes(data);
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, true, baseContext.clone(), cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        assertProperSegmentStateAndGet(storage);
        executor.waitForIdle();
        SplitFileInserterStorage resumed = createSplitFileInserterStorage(
            storage,
            data,
            cb,
            memoryLimitedJobRunner,
            keys
        );
        SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(resumed);
        for (int i = 0; i < segment.totalBlockCount; i++) {
            segment.onInsertedBlock(i, segment.encodeBlock(i).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertEquals(Status.SUCCEEDED, resumed.getStatus());
    }

    @Test
    public void testPersistentSmallSplitfileNoLastBlockCompletionAfterResume() throws Exception {
        data = generateData(random, size, bigRAFFactory);
        HashResult[] hashes = getHashes(data);
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, true, baseContext.clone(), cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        assertProperSegmentStateAndGet(storage);
        SplitFileInserterStorage resumed = null;
        for (int i = 0; i < storage.segments[0].totalBlockCount; i++) {
            executor.waitForIdle();
            resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
            SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(resumed);
            segment.onInsertedBlock(i, segment.encodeBlock(i).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertNotNull(resumed);
        assertEquals(Status.SUCCEEDED, resumed.getStatus());
    }

    @Test
    public void testPersistentSmallSplitfileWithLastBlockCompletionAfterResume() throws Exception {
        data = generateData(random, size, bigRAFFactory);
        hashes = getHashes(data);
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, true, baseContext.clone(), cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        assertProperSegmentStateAndGet(storage);
        SplitFileInserterStorage resumed = null;
        for (int i = 0; i < storage.segments[0].totalBlockCount; i++) {
            executor.waitForIdle();
            resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
            SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(resumed);
            segment.onInsertedBlock(i, segment.encodeBlock(i).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertNotNull(resumed);
        assertEquals(Status.SUCCEEDED, resumed.getStatus());
    }

    @Test
    public void testPersistentSmallSplitfileNoLastBlockFailAfterResume() throws Exception {
        data = generateData(random, size, bigRAFFactory);
        hashes = getHashes(data);
        context.consecutiveRNFsCountAsSuccess = 0;
        context.maxInsertRetries = 2;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, true, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        assertProperSegmentStateAndGet(storage);
        SplitFileInserterStorage resumed = null;

        for (int i = 0; i < 3; i++) {
            executor.waitForIdle();
            resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
            SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(resumed);
            segment.onFailure(0, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
        }

        InsertException e = assertThrows(InsertException.class, () -> cb.waitForSucceededInsert());
        assertEquals(InsertExceptionMode.TOO_MANY_RETRIES_IN_BLOCKS, e.mode);
        assertNotNull(e.errorCodes);
        assertEquals(3, e.errorCodes.getErrorCount(InsertExceptionMode.ROUTE_NOT_FOUND));
        assertEquals(3, e.errorCodes.totalCount());
        assertEquals(Status.FAILED, resumed.getStatus());
    }

    @Test
    public void testPersistentSmallSplitfileNoLastBlockChooseAfterResume() throws Exception {
        LockableRandomAccessBuffer data = generateData(random, size, bigRAFFactory);
        HashResult[] hashes = getHashes(data);
        context.consecutiveRNFsCountAsSuccess = 0;
        context.maxInsertRetries = 1;
        SplitFileInserterStorage storage = createSplitFileInserterStorage(data, size, cb, true, context, cryptoAlgorithm, cryptoKey, hashes, random, memoryLimitedJobRunner, keys);
        assertProperSegmentStateAndGet(storage);
        SplitFileInserterStorage resumed = null;
        int totalBlockCount = storage.segments[0].totalBlockCount;

        boolean[] chosenBlocks = new boolean[totalBlockCount];
        // Choose and fail all blocks.
        for (int i = 0; i < totalBlockCount; i++) {
            executor.waitForIdle();
            resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
            SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(resumed);

            BlockInsert chosen = segment.chooseBlock();
            assertNotNull(chosen);
            keys.addInsert(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onFailure(chosen.blockNumber, new InsertException(InsertExceptionMode.ROUTE_NOT_FOUND));
        }
        keys.clear();
        // Choose and succeed all blocks.
        chosenBlocks = new boolean[totalBlockCount];
        for (int i = 0; i < totalBlockCount; i++) {
            executor.waitForIdle();
            resumed = createSplitFileInserterStorage(storage, data, cb, memoryLimitedJobRunner, keys);
            SplitFileInserterSegmentStorage segment = assertProperSegmentStateAndGet(resumed);

            BlockInsert chosen = segment.chooseBlock();
            keys.addInsert(chosen);
            assertNotNull(chosen);
            assertFalse(chosenBlocks[chosen.blockNumber]);
            chosenBlocks[chosen.blockNumber] = true;
            segment.onInsertedBlock(chosen.blockNumber, segment.encodeBlock(chosen.blockNumber).getClientKey());
        }
        cb.waitForSucceededInsert();
        assertNotNull(resumed);
        assertEquals(Status.SUCCEEDED, resumed.getStatus());
    }

    private SplitFileInserterStorage createSplitFileInserterStorage(
        SplitFileInserterStorage storage,
        LockableRandomAccessBuffer data,
        MyCallback cb,
        MemoryLimitedJobRunner memoryLimitedJobRunner,
        MyKeysFetchingLocally keys
    ) throws Exception {
        return new SplitFileInserterStorage(
            storage.getRAF(),
            data,
            cb,
            random,
            memoryLimitedJobRunner,
            jobRunner,
            ticker,
            keys,
            fg,
            persistentFileTracker,
            null
        );
    }

    private SplitFileInserterStorage createSplitFileInserterStorage(
        LockableRandomAccessBuffer data,
        long size,
        MyCallback cb,
        boolean persistent,
        InsertContext baseContext,
        byte cryptoAlgorithm,
        byte[] cryptoKey,
        HashResult[] hashes,
        Random r,
        MemoryLimitedJobRunner memoryLimitedJobRunner,
        KeysFetchingLocally keys
    ) throws Exception {
        SplitFileInserterStorage storage = new SplitFileInserterStorage(
            data,
            size,
            cb,
            null,
            new ClientMetadata(),
            false,
            null,
            smallRAFFactory,
            persistent,
            baseContext,
            cryptoAlgorithm,
            cryptoKey,
            null,
            hashes,
            smallBucketFactory,
            checker,
            r,
            memoryLimitedJobRunner,
            jobRunner,
            ticker,
            keys,
            false,
            0,
            0,
            0,
            0
        );
        storage.start();
        cb.waitForFinishedEncode();
        return storage;
    }

    private SplitFileFetcherStorage createFetcherStorage(
        Metadata m1,
        FetchCallbackForTestingSplitFileInserter fcb,
        ArrayList<COMPRESSOR_TYPE> decompressors,
        short cmode,
        FetchContext fctx,
        RandomSource r
    ) throws FetchException, MetadataParseException, IOException {
        return new SplitFileFetcherStorage(
            m1,
            fcb,
            decompressors,
            new ClientMetadata(),
            false,
            cmode,
            fctx,
            false,
            salt,
            URI,
            URI,
            true,
            new byte[0],
            r,
            smallBucketFactory,
            smallRAFFactory,
            jobRunner,
            ticker,
            memoryLimitedJobRunner,
            checker,
            false,
            null,
            null,
            keys
        );
    }

    private SplitFileInserterSegmentStorage assertProperSegmentStateAndGet(SplitFileInserterStorage storage) {
        assertEquals(1, storage.segments.length);
        SplitFileInserterSegmentStorage segment = storage.segments[0];
        assertEquals(2, segment.dataBlockCount);
        assertEquals(3, segment.checkBlockCount);
        assertEquals(0, segment.crossCheckBlockCount);
        assertEquals(Status.ENCODED, storage.getStatus());
        return segment;
    }
}
