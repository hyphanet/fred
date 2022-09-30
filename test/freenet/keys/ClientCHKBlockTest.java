package freenet.keys;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import junit.framework.TestCase;

import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.math.MersenneTwister;

public class ClientCHKBlockTest extends TestCase {

	public void testEncodeDecodeEmptyBlock() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {
		byte[] buf = new byte[0];
		checkBlock(buf, false);
		checkBlock(buf, true);
	}
	
	public void testEncodeDecodeFullBlock() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {
		byte[] fullBlock = new byte[CHKBlock.DATA_LENGTH];
		MersenneTwister random = new MersenneTwister(42);
		for(int i=0;i<10;i++) {
			random.nextBytes(fullBlock);
			checkBlock(fullBlock, false);
			checkBlock(fullBlock, true);
		}
	}

	public void testEncodeDecodeShortInteger() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {
		for(int i=0;i<100;i++) {
			String s = Integer.toString(i);
			checkBlock(s.getBytes("UTF-8"), false);
			checkBlock(s.getBytes("UTF-8"), true);
		}
	}
	
	public void testEncodeDecodeRandomLength() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {	
		MersenneTwister random = new MersenneTwister(42);
		for(int i=0;i<10;i++) {
			byte[] buf = new byte[random.nextInt(CHKBlock.DATA_LENGTH+1)];
			random.nextBytes(buf);
			checkBlock(buf, false);
			checkBlock(buf, true);
		}
	}
	
	public void testEncodeDecodeNearlyFullBlock() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {	
		MersenneTwister random = new MersenneTwister(68);
		for(int i=0;i<10;i++) {
			byte[] buf = new byte[CHKBlock.DATA_LENGTH - i];
			random.nextBytes(buf);
			checkBlock(buf, false);
			checkBlock(buf, true);
		}
		for(int i=0;i<10;i++) {
			byte[] buf = new byte[CHKBlock.DATA_LENGTH - (1<<i)];
			random.nextBytes(buf);
			checkBlock(buf, false);
			checkBlock(buf, true);
		}
	}
	
	private void checkBlock(byte[] data, boolean newAlgo) throws CHKEncodeException, InvalidCompressionCodecException, CHKVerifyException, CHKDecodeException, IOException {
		byte cryptoAlgorithm = newAlgo ? Key.ALGO_AES_CTR_256_SHA256 : Key.ALGO_AES_PCFB_256_SHA256;
		byte[] copyOfData = new byte[data.length];
		System.arraycopy(data, 0, copyOfData, 0, data.length);
		ClientCHKBlock encodedBlock =
			ClientCHKBlock.encode(new ArrayBucket(data), false, false, (short)-1, data.length, null,
					null, cryptoAlgorithm);
		// Not modified in-place.
		assert(Arrays.equals(data, copyOfData));
		ClientCHK key = encodedBlock.getClientKey();
		if(newAlgo) {
			// Check with no JCA.
			ClientCHKBlock otherEncodedBlock =
				ClientCHKBlock.encode(new ArrayBucket(data), false, false, (short)-1, data.length, null,
            null, cryptoAlgorithm, true);
			assertTrue(key.equals(otherEncodedBlock.getClientKey()));
			assertTrue(Arrays.equals(otherEncodedBlock.getBlock().data, encodedBlock.getBlock().data));
			assertTrue(Arrays.equals(otherEncodedBlock.getBlock().headers, encodedBlock.getBlock().headers));
		}
		// Verify it.
		CHKBlock block = CHKBlock.construct(encodedBlock.getBlock().data, encodedBlock.getBlock().headers, cryptoAlgorithm);
		ClientCHKBlock checkBlock = new ClientCHKBlock(block, key);
		ArrayBucket checkData = (ArrayBucket) checkBlock.decode(new ArrayBucketFactory(), data.length, false);
		assert(Arrays.equals(checkData.toByteArray(), data));
		if(newAlgo) {
			checkData = (ArrayBucket) checkBlock.decode(new ArrayBucketFactory(), data.length, false, true);
			assert(Arrays.equals(checkData.toByteArray(), data));
		}
	}

}
