package freenet.store.saltedhash;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import junit.framework.TestCase;
import freenet.keys.CHKBlock;
import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.node.SemiOrderedShutdownHook;
import freenet.store.CHKStore;
import freenet.support.PooledExecutor;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.TestProperty;
import freenet.support.Ticker;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.FileUtil;

/** Test the slot filter mechanism */
public class SaltedHashSlotFilterTest extends TestCase {

	private Random weakPRNG = new Random(12340);
	private PooledExecutor exec = new PooledExecutor();
	private Ticker ticker = new TrivialTicker(exec);
	private File tempDir = new File("tmp-cachingfreenetstoretest");;
	private static final int TEST_COUNT = TestProperty.EXTENSIVE ? 100 : 20;
	private static final int ACCEPTABLE_FALSE_POSITIVES = TestProperty.EXTENSIVE ? 5 : 2;
	private static final int STORE_SIZE = TEST_COUNT * 5;

	@Override
	protected void setUp() throws java.lang.Exception {
		exec.start();
		SaltedHashFreenetStore.NO_CLEANER_SLEEP = false;

		// Make sure each test case starts with a nice empty temporary directory
		if( ! FileUtil.removeAll(tempDir) ) {
			throw new RuntimeException("Could not clean temporary directory for running clean tests");
		}
		tempDir.mkdir();
	}

	@Override
	protected void tearDown() {
		FileUtil.removeAll(tempDir);
	}

	private int populateStore(CHKStore store, SaltedHashFreenetStore<CHKBlock> saltStore, int numKeys)
			throws CHKEncodeException, IOException, CHKVerifyException, CHKDecodeException {
		int falsePositives = 0;
		for (int i = 0; i < numKeys; i++) {
			String testValue = "test" + i;
			ClientCHKBlock block = encodeBlockCHK(testValue);
			ClientCHK key = block.getClientKey();
			byte[] routingKey = key.getRoutingKey();
			if (saltStore.probablyInStore(routingKey)) {
				falsePositives++;
			}
			store.put(block.getBlock(), false);
			assertTrue(saltStore.probablyInStore(routingKey));
			CHKBlock verifyBlock = store.fetch(key.getNodeCHK(), false, false, null);
			String verifyValue = decodeBlockCHK(verifyBlock, key);
			assertEquals(testValue, verifyValue);
		}
		return falsePositives;
	}

	private void checkStore(CHKStore store, SaltedHashFreenetStore<CHKBlock> saltStore, int numKeys)
			throws CHKEncodeException, CHKVerifyException, CHKDecodeException, IOException {
		checkStore(store, saltStore, numKeys, false);
	}

	private void checkStore(CHKStore store, SaltedHashFreenetStore<CHKBlock> saltStore, int numKeys, boolean requireAll)
			throws CHKEncodeException, IOException, CHKVerifyException, CHKDecodeException {
		boolean atLeastOneKey = false;
		for (int i = 0; i < numKeys; i++) {
			String value = "test" + i;
			ClientCHKBlock block = encodeBlockCHK(value);
			ClientCHK key = block.getClientKey();
			byte[] routingKey = key.getRoutingKey();
			CHKBlock verifyBlock = store.fetch(key.getNodeCHK(), false, false, null);
			if (!requireAll && verifyBlock == null) {
				continue;
			}

			assertTrue(saltStore.probablyInStore(routingKey));
			String verifyValue = decodeBlockCHK(verifyBlock, key);
			assertEquals(value, verifyValue);

			atLeastOneKey = true;
		}
		assertTrue("At least one key should have been present", atLeastOneKey);
	}

	public void testCHKPresent_writeImmediately()
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		checkCHKPresent(-1, TEST_COUNT, ACCEPTABLE_FALSE_POSITIVES, STORE_SIZE);
	}

	// Much longer than the test will take.
	public void testCHKPresent_veryLongPersistanceTime()
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		checkCHKPresent(600 * 1000, TEST_COUNT, ACCEPTABLE_FALSE_POSITIVES, STORE_SIZE);
	}

	// Check that it doesn't reuse slots if it can avoid it.
	public void testCHKPresent_noReuseSlots()
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		checkCHKPresent(-1, SaltedHashFreenetStore.OPTION_MAX_PROBE, 1, SaltedHashFreenetStore.OPTION_MAX_PROBE);
	}

	public void testCHKPresent_smallStoreSpace()
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		checkCHKPresent(-1, 10, 1, 20);
	}

	private void checkCHKPresent(int persistenceTime, int testCount, int acceptableFalsePositives, int storeSize)
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		ResizablePersistentIntBuffer.setPersistenceTime(persistenceTime);
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);

		CHKStore store = new CHKStore();
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, storeSize, true, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);

			int falsePositives = populateStore(store, saltStore, testCount);

			assertTrue(falsePositives <= acceptableFalsePositives);

			checkStore(store, saltStore, testCount, true);
		}
	}

	public void testCHKPresentWithClose_writeImmediately()
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		checkCHKPresentWithClose(-1);
	}

	public void testCHKPresentWithClose_veryLongPersistanceTime()
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		checkCHKPresentWithClose(600 * 1000); // Much longer than the test will take.
	}

	public void checkCHKPresentWithClose(int persistenceTime)
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		ResizablePersistentIntBuffer.setPersistenceTime(persistenceTime);
		File f = new File(tempDir, "saltstore");

		CHKStore store = new CHKStore();
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, STORE_SIZE, true, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);

			int falsePositives = populateStore(store, saltStore, TEST_COUNT);

			assertTrue(falsePositives <= ACCEPTABLE_FALSE_POSITIVES);
		}

		store = new CHKStore();
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, STORE_SIZE, true, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);

			checkStore(store, saltStore, TEST_COUNT);
		}
	}

	public void testCHKPresentWithAbort() throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		ResizablePersistentIntBuffer.setPersistenceTime(1000);
		File f = new File(tempDir, "saltstore");

		CHKStore store = new CHKStore();
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, STORE_SIZE, true, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);

			int falsePositives = populateStore(store, saltStore, TEST_COUNT);

			assertTrue(falsePositives <= ACCEPTABLE_FALSE_POSITIVES);

			saltStore.close(true);
		}

		store = new CHKStore();
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, STORE_SIZE, true, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);

			checkStore(store, saltStore, TEST_COUNT);
		}
	}

	public void testCHKDelayedTurnOnSlotFilters()
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		ResizablePersistentIntBuffer.setPersistenceTime(1000);
		File f = new File(tempDir, "saltstore");

		CHKStore store = new CHKStore();
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, STORE_SIZE, false, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);

			int falsePositives = populateStore(store, saltStore, TEST_COUNT);

			assertTrue(falsePositives == TEST_COUNT);
		}

		store = new CHKStore();
		// Now turn on slot filters. Does it still work?
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, STORE_SIZE, true, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);

			checkStore(store, saltStore, TEST_COUNT);
		}
	}

	public void testCHKDelayedTurnOnSlotFiltersWithCleaner()
			throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException, InterruptedException {
		ResizablePersistentIntBuffer.setPersistenceTime(1000);
		File f = new File(tempDir, "saltstore");

		CHKStore store = new CHKStore();
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, STORE_SIZE, false, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);

			int falsePositives = populateStore(store, saltStore, TEST_COUNT);

			assertTrue(falsePositives == TEST_COUNT);
		}

		store = new CHKStore();
		// Now turn on slot filters. Does it still work?
		SaltedHashFreenetStore.NO_CLEANER_SLEEP = true;
		try (SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK",
				store, weakPRNG, STORE_SIZE, true, SemiOrderedShutdownHook.get(), true, true, ticker, null)) {
			saltStore.start(null, true);
			saltStore.testingWaitForCleanerDone(50, 100);

			checkStore(store, saltStore, TEST_COUNT);
		}
	}

	private String decodeBlockCHK(CHKBlock verify, ClientCHK key)
			throws CHKVerifyException, CHKDecodeException, IOException {
		ClientCHKBlock cb = new ClientCHKBlock(verify, key);
		Bucket output = cb.decode(new ArrayBucketFactory(), 32768, false);
		byte[] buf = BucketTools.toByteArray(output);
		return new String(buf, "UTF-8");
	}

	private ClientCHKBlock encodeBlockCHK(String test) throws CHKEncodeException, IOException {
		byte[] data = test.getBytes("UTF-8");
		SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(data);
		return ClientCHKBlock.encode(bucket, false, false, (short) -1, bucket.size(),
				Compressor.DEFAULT_COMPRESSORDESCRIPTOR, null, (byte) 0);
	}

}
