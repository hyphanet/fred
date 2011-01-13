package freenet.store;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import junit.framework.TestCase;
import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.support.PooledExecutor;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.FileUtil;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.TempBucketFactory;

public class SlashdotStoreTest extends TestCase {
	
	private RandomSource strongPRNG = new DummyRandomSource(43210);
	private Random weakPRNG = new Random(12340);
	private PooledExecutor exec = new PooledExecutor();
	private FilenameGenerator fg;
	private TempBucketFactory tbf;
	private File tempDir;

	protected void setUp() throws java.lang.Exception {
		tempDir = new File("tmp-slashdotstoretest");
		tempDir.mkdir();
		fg = new FilenameGenerator(weakPRNG, true, tempDir, "temp-");
		tbf = new TempBucketFactory(exec, fg, 4096, 65536, strongPRNG, weakPRNG, false);
		exec.start();
	}
	
	protected void tearDown() {
		FileUtil.removeAll(tempDir);
	}
	
	public void testSimple() throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException {
		CHKStore store = new CHKStore();
		new SlashdotStore<CHKBlock>(store, 10, 30*1000, 5*1000, new TrivialTicker(exec), tbf);
		
		// Encode a block
		String test = "test";
		ClientCHKBlock block = encodeBlock(test);
		store.put(block, false);
		
		ClientCHK key = block.getClientKey();
		
		CHKBlock verify = store.fetch(key.getNodeCHK(), false, false, null);
		String data = decodeBlock(verify, key);
		assertEquals(test, data);
	}
	
	public void testDeletion() throws IOException, CHKEncodeException, CHKVerifyException, CHKDecodeException, InterruptedException {
		CHKStore store = new CHKStore();
		new SlashdotStore<CHKBlock>(store, 10, 500, 100, new TrivialTicker(exec), tbf);
		
		// Encode a block
		String test = "test";
		ClientCHKBlock block = encodeBlock(test);
		store.put(block, false);
		
		Thread.sleep(2000);
		
		ClientCHK key = block.getClientKey();
		
		CHKBlock verify = store.fetch(key.getNodeCHK(), false, false, null);
		if(verify == null) return; // Expected outcome
		String data = decodeBlock(verify, key);
		System.err.println("Got data: "+data+" but should have been deleted!");
		assertTrue(false);
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
