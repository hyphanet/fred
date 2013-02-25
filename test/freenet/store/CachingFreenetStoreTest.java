package freenet.store;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import junit.framework.TestCase;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DummyRandomSource;
import freenet.crypt.Global;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.keys.CHKBlock;
import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientSSK;
import freenet.keys.ClientSSKBlock;
import freenet.keys.InsertableClientSSK;
import freenet.keys.Key;
import freenet.keys.KeyDecodeException;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKEncodeException;
import freenet.keys.SSKVerifyException;
import freenet.node.SemiOrderedShutdownHook;
import freenet.store.saltedhash.ResizablePersistentIntBuffer;
import freenet.store.saltedhash.SaltedHashFreenetStore;
import freenet.support.Fields;
import freenet.support.PooledExecutor;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.Ticker;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.FileUtil;

/**
 * CachingFreenetStoreTest
 * Test for CachingFreenetStore
 * 
 * @author Simon Vocella <voxsim@gmail.com>
 * 
 */
public class CachingFreenetStoreTest extends TestCase {

	private Random weakPRNG = new Random(12340);
	private PooledExecutor exec = new PooledExecutor();
	private Ticker ticker = new TrivialTicker(exec);
	private File tempDir;
	private long cachingFreenetStoreMaxSize = Fields.parseLong("1M");
	private long cachingFreenetStorePeriod = Fields.parseLong("300k");

	@Override
	protected void setUp() throws java.lang.Exception {
		tempDir = new File("tmp-cachingfreenetstoretest");
		tempDir.mkdir();
		exec.start();
		ResizablePersistentIntBuffer.setPersistenceTime(-1);
	}

	@Override
	protected void tearDown() {
		FileUtil.removeAll(tempDir);
	}
	
	/* Simple test with CHK for CachingFreenetStore */
	public void testSimpleCHK() throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);

		CHKStore store = new CHKStore();
		SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<CHKBlock> cachingStore = new CachingFreenetStore<CHKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore, ticker);
		cachingStore.start(null, true);

		for(int i=0;i<5;i++) {
			String test = "test" + i;
			ClientCHKBlock block = encodeBlockCHK(test);
			store.put(block.getBlock(), false);
			ClientCHK key = block.getClientKey();
			// Check that it's in the cache, *not* the underlying store.
			assertEquals(saltStore.fetch(key.getRoutingKey(), key.getNodeCHK().getFullKey(), false, false, false, false, null), null);
			CHKBlock verify = store.fetch(key.getNodeCHK(), false, false, null);
			String data = decodeBlockCHK(verify, key);
			assertEquals(test, data);
		}
		
		cachingStore.close();
	}
	
	/* Check that if the size limit is 0 (and therefore presumably if it is smaller than the key being
	 * cached), we will pass through immediately. */
	public void testZeroSize() throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);

		CHKStore store = new CHKStore();
		SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreCHK", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<CHKBlock> cachingStore = new CachingFreenetStore<CHKBlock>(store, 0, cachingFreenetStorePeriod, saltStore, ticker);
		cachingStore.start(null, true);

		for(int i=0;i<5;i++) {
			String test = "test" + i;
			ClientCHKBlock block = encodeBlockCHK(test);
			store.put(block.getBlock(), false);
			ClientCHK key = block.getClientKey();
			// It should pass straight through.
			assertNotNull(saltStore.fetch(key.getRoutingKey(), key.getNodeCHK().getFullKey(), false, false, false, false, null));
			CHKBlock verify = store.fetch(key.getNodeCHK(), false, false, null);
			String data = decodeBlockCHK(verify, key);
			assertEquals(test, data);
		}
		
		cachingStore.close();
	}
	
	/* Simple test with SSK for CachingFreenetStore */
	public void testSimpleSSK() throws IOException, KeyCollisionException, SSKVerifyException, KeyDecodeException, SSKEncodeException, InvalidCompressionCodecException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);

		final int keys = 5;
		PubkeyStore pk = new PubkeyStore();
		new RAMFreenetStore<DSAPublicKey>(pk, keys);
		GetPubkey pubkeyCache = new SimpleGetPubkey(pk);
		SSKStore store = new SSKStore(pubkeyCache);
		SaltedHashFreenetStore<SSKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreSSK", store, weakPRNG, 20, true, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<SSKBlock> cachingStore = new CachingFreenetStore<SSKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore, ticker);
		cachingStore.start(null, true);
		RandomSource random = new DummyRandomSource(12345);

		for(int i=0;i<5;i++) {
			String test = "test" + i;
			ClientSSKBlock block = encodeBlockSSK(test, random);
			SSKBlock sskBlock = (SSKBlock) block.getBlock();
			store.put(sskBlock, false, false);
			ClientSSK key = block.getClientKey();
			NodeSSK ssk = (NodeSSK) key.getNodeKey();
			pubkeyCache.cacheKey(ssk.getPubKeyHash(), ssk.getPubKey(), false, false, false, false, false);
			// Check that it's in the cache, *not* the underlying store.
			assertEquals(saltStore.fetch(ssk.getRoutingKey(), ssk.getFullKey(), false, false, false, false, null), null);
			SSKBlock verify = store.fetch(ssk, false, false, false, false, null);
			String data = decodeBlockSSK(verify, key);
			assertEquals(test, data);
		}
		
		cachingStore.close();
	}
	
	/* Test to re-open after close */
	public void testOnCloseCHK() throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);

		CHKStore store = new CHKStore();
		SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreOnClose", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<CHKBlock> cachingStore = new CachingFreenetStore<CHKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore, ticker);
		cachingStore.start(null, true);
		
		List<ClientCHKBlock> chkBlocks = new ArrayList<ClientCHKBlock>();
		List<String> tests = new ArrayList<String>();

		for(int i=0;i<5;i++) {
			String test = "test" + i;
			ClientCHKBlock block = encodeBlockCHK(test);
			// Check that it's in the cache, *not* the underlying store.
			assertEquals(saltStore.fetch(block.getKey().getRoutingKey(), block.getKey().getFullKey(), false, false, false, false, null), null);
			store.put(block.getBlock(), false);
			tests.add(test);
			chkBlocks.add(block);
		}
		
		cachingStore.close();
		
		SaltedHashFreenetStore<CHKBlock> saltStore2 = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreOnClose", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		cachingStore = new CachingFreenetStore<CHKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore2, ticker);
		cachingStore.start(null, true);

		for(int i=0;i<5;i++) {
			String test = tests.remove(0); //get the first element
			ClientCHKBlock block = chkBlocks.remove(0); //get the first element
			ClientCHK key = block.getClientKey();
			CHKBlock verify = store.fetch(key.getNodeCHK(), false, false, null);
			String data = decodeBlockCHK(verify, key);
			assertEquals(test, data);
		}
		
		cachingStore.close();
	}
	
	/* Test whether stuff gets written to disk after the caching period expires */
	public void testTimeExpireCHK() throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);
		long delay = 100;
		
		CHKStore store = new CHKStore();
		SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreTimeExpire", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<CHKBlock> cachingStore = new CachingFreenetStore<CHKBlock>(store, cachingFreenetStoreMaxSize, delay, saltStore, ticker);
		cachingStore.start(null, true);
		
		List<ClientCHKBlock> chkBlocks = new ArrayList<ClientCHKBlock>();
		List<String> tests = new ArrayList<String>();
		
		// Put five chk blocks 
		for(int i=0;i<5;i++) {
			String test = "test" + i;
			ClientCHKBlock block = encodeBlockCHK(test);
			store.put(block.getBlock(), false);
			// Check that it's in the cache, *not* the underlying store.
			assertEquals(saltStore.fetch(block.getKey().getRoutingKey(), block.getKey().getFullKey(), false, false, false, false, null), null);
			tests.add(test);
			chkBlocks.add(block);
		}
		
		try {
			Thread.sleep(2*delay);
		} catch (InterruptedException e) {
			// Ignore
		}
		
		//Fetch five chk blocks
		for(int i=0; i<5; i++){
			String test = tests.remove(0); //get the first element
			ClientCHKBlock block = chkBlocks.remove(0); //get the first element
			ClientCHK key = block.getClientKey();
			CHKBlock verify = store.fetch(key.getNodeCHK(), false, false, null);
			String data = decodeBlockCHK(verify, key);
			assertEquals(test, data);
			// Check that it's in the underlying store now.
			assertNotNull(saltStore.fetch(block.getKey().getRoutingKey(), block.getKey().getFullKey(), false, false, false, false, null));
		}
		
		cachingStore.close();
	}

	private String decodeBlockCHK(CHKBlock verify, ClientCHK key) throws CHKVerifyException, CHKDecodeException, IOException {
		ClientCHKBlock cb = new ClientCHKBlock(verify, key);
		Bucket output = cb.decode(new ArrayBucketFactory(), 32768, false);
		byte[] buf = BucketTools.toByteArray(output);
		return new String(buf, "UTF-8");
	}

	private ClientCHKBlock encodeBlockCHK(String test) throws CHKEncodeException, IOException {
		byte[] data = test.getBytes("UTF-8");
		SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(data);
		return ClientCHKBlock.encode(bucket, false, false, (short)-1, bucket.size(), Compressor.DEFAULT_COMPRESSORDESCRIPTOR, false, null, (byte)0);
	}
	
	/* Test with SSK to re-open after close */
	public void testOnCloseSSK() throws IOException, SSKEncodeException, InvalidCompressionCodecException, KeyCollisionException, SSKVerifyException, KeyDecodeException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);

		final int keys = 5;
		PubkeyStore pk = new PubkeyStore();
		new RAMFreenetStore<DSAPublicKey>(pk, keys);
		GetPubkey pubkeyCache = new SimpleGetPubkey(pk);
		SSKStore store = new SSKStore(pubkeyCache);
		SaltedHashFreenetStore<SSKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreOnCloseSSK", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<SSKBlock> cachingStore = new CachingFreenetStore<SSKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore, ticker);
		cachingStore.start(null, true);
		RandomSource random = new DummyRandomSource(12345);
		
		List<ClientSSKBlock> sskBlocks = new ArrayList<ClientSSKBlock>();
		List<String> tests = new ArrayList<String>();

		for(int i=0;i<5;i++) {
			String test = "test" + i;
			ClientSSKBlock block = encodeBlockSSK(test, random);
			SSKBlock sskBlock = (SSKBlock) block.getBlock();
			store.put(sskBlock, false, false);
			pubkeyCache.cacheKey(sskBlock.getKey().getPubKeyHash(), sskBlock.getKey().getPubKey(), false, false, false, false, false);
			tests.add(test);
			sskBlocks.add(block);
		}
		
		cachingStore.close();
		
		SaltedHashFreenetStore<SSKBlock> saltStore2 = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreOnCloseSSK", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		cachingStore = new CachingFreenetStore<SSKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore2, ticker);
		cachingStore.start(null, true);
		

		for(int i=0;i<5;i++) {
			String test = tests.remove(0); //get the first element
			ClientSSKBlock block = sskBlocks.remove(0); //get the first element
			ClientSSK key = block.getClientKey();
			NodeSSK ssk = (NodeSSK) key.getNodeKey();
			SSKBlock verify = store.fetch(ssk, false, false, false, false, null);
			String data = decodeBlockSSK(verify, key);
			assertEquals(test, data);
		}
		
		cachingStore.close();
	}
	
	/* Test with SSK whether stuff gets written to disk after the caching period expires */
	public void testTimeExpireSSK() throws IOException, SSKEncodeException, InvalidCompressionCodecException, KeyCollisionException, SSKVerifyException, KeyDecodeException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);
		long delay = 100;
		
		final int keys = 5;
		PubkeyStore pk = new PubkeyStore();
		new RAMFreenetStore<DSAPublicKey>(pk, keys);
		GetPubkey pubkeyCache = new SimpleGetPubkey(pk);
		SSKStore store = new SSKStore(pubkeyCache);
		SaltedHashFreenetStore<SSKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreOnCloseSSK", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<SSKBlock> cachingStore = new CachingFreenetStore<SSKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore, ticker);
		cachingStore.start(null, true);
		RandomSource random = new DummyRandomSource(12345);
		
		List<ClientSSKBlock> sskBlocks = new ArrayList<ClientSSKBlock>();
		List<String> tests = new ArrayList<String>();

		for(int i=0;i<5;i++) {
			String test = "test" + i;
			ClientSSKBlock block = encodeBlockSSK(test, random);
			SSKBlock sskBlock = (SSKBlock) block.getBlock();
			store.put(sskBlock, false, false);
			pubkeyCache.cacheKey(sskBlock.getKey().getPubKeyHash(), sskBlock.getKey().getPubKey(), false, false, false, false, false);
			tests.add(test);
			sskBlocks.add(block);
		}
		
		try {
			Thread.sleep(2*delay);
		} catch (InterruptedException e) {
			// Ignore
		}
		
		for(int i=0;i<5;i++) {
			String test = tests.remove(0); //get the first element
			ClientSSKBlock block = sskBlocks.remove(0); //get the first element
			ClientSSK key = block.getClientKey();
			NodeSSK ssk = (NodeSSK) key.getNodeKey();
			SSKBlock verify = store.fetch(ssk, false, false, false, false, null);
			String data = decodeBlockSSK(verify, key);
			assertEquals(test, data);
		}
		
		cachingStore.close();
	}

	public void testOnCollisionsSSK() throws IOException, SSKEncodeException, InvalidCompressionCodecException, SSKVerifyException, KeyDecodeException, KeyCollisionException {
		// With slot filters turned off, it goes straight to disk, because probablyInStore() always returns true.
		checkOnCollisionsSSK(false);
		// With slot filters turned on, it should be cached, it should compare it, and still not throw if it's the same block.
		checkOnCollisionsSSK(true);
	}
	
	/* Test collisions on SSK */
	private void checkOnCollisionsSSK(boolean useSlotFilter) throws IOException, SSKEncodeException, InvalidCompressionCodecException, SSKVerifyException, KeyDecodeException, KeyCollisionException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);

		final int keys = 5;
		PubkeyStore pk = new PubkeyStore();
		new RAMFreenetStore<DSAPublicKey>(pk, keys);
		GetPubkey pubkeyCache = new SimpleGetPubkey(pk);
		SSKStore store = new SSKStore(pubkeyCache);
		SaltedHashFreenetStore<SSKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStoreOnCloseSSK", store, weakPRNG, 10, useSlotFilter, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<SSKBlock> cachingStore = new CachingFreenetStore<SSKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore, ticker);
		cachingStore.start(null, true);
		RandomSource random = new DummyRandomSource(12345);
		
		final int CRYPTO_KEY_LENGTH = 32;
		byte[] ckey = new byte[CRYPTO_KEY_LENGTH];
		random.nextBytes(ckey);
		DSAGroup g = Global.DSAgroupBigA;
		DSAPrivateKey privKey = new DSAPrivateKey(g, random);
		DSAPublicKey pubKey = new DSAPublicKey(g, privKey);
		byte[] pkHash = SHA256.digest(pubKey.asBytes());
		String docName = "myDOC";
		InsertableClientSSK ik = new InsertableClientSSK(docName, pkHash, pubKey, privKey, ckey, Key.ALGO_AES_PCFB_256_SHA256);
		
		String test = "test";
		SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(test.getBytes("UTF-8"));
		ClientSSKBlock block = ik.encode(bucket, false, false, (short)-1, bucket.size(), random, Compressor.DEFAULT_COMPRESSORDESCRIPTOR, false);
		SSKBlock sskBlock = (SSKBlock) block.getBlock();
		store.put(sskBlock, false, false);
		
		//If the block is the same then there should not be a collision
		try {
			store.put(sskBlock, false, false);
			assertTrue(true);
		} catch (KeyCollisionException e) {
			assertTrue(false);
			
		}
		
		String test1 = "test1";
		SimpleReadOnlyArrayBucket bucket1 = new SimpleReadOnlyArrayBucket(test1.getBytes("UTF-8"));
		ClientSSKBlock block1 = ik.encode(bucket1, false, false, (short)-1, bucket1.size(), random, Compressor.DEFAULT_COMPRESSORDESCRIPTOR, false);
		SSKBlock sskBlock1 = (SSKBlock) block1.getBlock();
		
		//if it's different (e.g. different content, same key), there should be a KCE thrown
		try {
			store.put(sskBlock1, false, false);
			assertTrue(false);
		} catch (KeyCollisionException e) {
			assertTrue(true);
		}
		
		// if overwrite is set, then no collision should be thrown
		try {
			store.put(sskBlock1, true, false);
			assertTrue(true);
		} catch (KeyCollisionException e) {
			assertTrue(false);
			
		}
		
		ClientSSK key = block1.getClientKey();
		pubkeyCache.cacheKey(sskBlock.getKey().getPubKeyHash(), sskBlock.getKey().getPubKey(), false, false, false, false, false);
		// Check that it's in the cache, *not* the underlying store.
		NodeSSK ssk = (NodeSSK) key.getNodeKey();
		SSKBlock verify = store.fetch(ssk, false, false, false, false, null);
		String data = decodeBlockSSK(verify, key);
		assertEquals(test1, data);
		
		if(useSlotFilter) {
			// Check that it's in the cache
			assertNull(saltStore.fetch(block.getKey().getRoutingKey(), block.getKey().getFullKey(), false, false, false, false, null));
		} else {
			// Check that it's in the underlying store now.
			assertNotNull(saltStore.fetch(block.getKey().getRoutingKey(), block.getKey().getFullKey(), false, false, false, false, null));
		}
		
		cachingStore.close();
	}
	
	private String decodeBlockSSK(SSKBlock verify, ClientSSK key) throws SSKVerifyException, KeyDecodeException, IOException {
		ClientSSKBlock cb = ClientSSKBlock.construct(verify, key);
		Bucket output = cb.decode(new ArrayBucketFactory(), 32768, false);
		byte[] buf = BucketTools.toByteArray(output);
		return new String(buf, "UTF-8");
	}

	private ClientSSKBlock encodeBlockSSK(String test, RandomSource random) throws IOException, SSKEncodeException, InvalidCompressionCodecException {
		byte[] data = test.getBytes("UTF-8");
		SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(data);
		InsertableClientSSK ik = InsertableClientSSK.createRandom(random, test);
		return ik.encode(bucket, false, false, (short)-1, bucket.size(), random, Compressor.DEFAULT_COMPRESSORDESCRIPTOR, false);
	}
}