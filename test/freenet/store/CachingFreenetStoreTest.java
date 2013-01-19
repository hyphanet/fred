package freenet.store;

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
import freenet.store.saltedhash.ResizablePersistentIntBuffer;
import freenet.store.saltedhash.SaltedHashFreenetStore;
import freenet.support.Fields;
import freenet.support.PooledExecutor;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.Ticker;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
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
	
	public void testCachingFreenetStore() throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		File f = new File(tempDir, "saltstore");
		FileUtil.removeAll(f);

		CHKStore store = new CHKStore();
		SaltedHashFreenetStore<CHKBlock> saltStore = SaltedHashFreenetStore.construct(f, "testCachingFreenetStore", store, weakPRNG, 10, false, SemiOrderedShutdownHook.get(), true, true, ticker, null);
		CachingFreenetStore<CHKBlock> cachingStore = new CachingFreenetStore<CHKBlock>(store, cachingFreenetStoreMaxSize, cachingFreenetStorePeriod, saltStore, ticker);
		cachingStore.start(null, true);

		for(int i=0;i<5;i++) {
			// Encode a block
			String test = "test" + i;
			ClientCHKBlock block = encodeBlock(test);
			store.put(block, false);
			
			ClientCHK key = block.getClientKey();
			
			CHKBlock verify = store.fetch(key.getNodeCHK(), false, false, null);
			String data = decodeBlock(verify, key);
			assertEquals(test, data);
		}
		
		cachingStore.close();
	}

	private String decodeBlock(CHKBlock verify, ClientCHK key) throws CHKVerifyException, CHKDecodeException, IOException {
		ClientCHKBlock cb = new ClientCHKBlock(verify, key);
		Bucket output = cb.decode(new ArrayBucketFactory(), 32768, false);
		byte[] buf = BucketTools.toByteArray(output);
		return new String(buf, "UTF-8");
	}

	private ClientCHKBlock encodeBlock(String test) throws CHKEncodeException, IOException {
		byte[] data = test.getBytes("UTF-8");
		SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(data);
		return ClientCHKBlock.encode(bucket, false, false, (short)-1, bucket.size(), Compressor.DEFAULT_COMPRESSORDESCRIPTOR, false, null, (byte)0);
	}
}
