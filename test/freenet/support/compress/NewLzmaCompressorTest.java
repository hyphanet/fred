/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

import junit.framework.TestCase;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.NullBucket;

/**
 * Test case for {@link freenet.support.compress.Bzip2Compressor} class.
 */
public class NewLzmaCompressorTest extends TestCase {

	private static final String UNCOMPRESSED_DATA_1 = GzipCompressorTest.UNCOMPRESSED_DATA_1;

	private static final byte[] COMPRESSED_DATA_1 = {
		104,57,49,65,89,38,83,89,-18,-87,-99,-74,0,0,33,-39,-128,0,8,16,
		0,58,64,52,-7,-86,0,48,0,-69,65,76,38,-102,3,76,65,-92,-12,-43,
		61,71,-88,-51,35,76,37,52,32,19,-44,67,74,-46,-9,17,14,-35,55,
		100,-10,73,-75,121,-34,83,56,-125,15,32,-118,35,66,124,-120,-39,
		119,-104,-108,66,101,-56,94,-71,-41,-43,68,51,65,19,-44,-118,4,
		-36,-117,33,-101,-120,-49,-10,17,-51,-19,28,76,-57,-112,-68,-50,
		-66,-60,-43,-81,127,-51,-10,58,-92,38,18,45,102,117,-31,-116,
		-114,-6,-87,-59,-43,-106,41,-30,-63,-34,-39,-117,-104,-114,100,
		-115,36,-112,23,104,-110,71,-45,-116,-23,-85,-36,-24,-61,14,32,
		105,55,-105,-31,-4,93,-55,20,-31,66,67,-70,-90,118,-40
	};

	/**
	 * test BZIP2 compressor's identity and functionality
	 */
	public void testNewLzmaCompressor() throws IOException {
		Compressor.COMPRESSOR_TYPE lzcompressor = Compressor.COMPRESSOR_TYPE.LZMA_NEW;
		Compressor compressorZero = Compressor.COMPRESSOR_TYPE.getCompressorByMetadataID((short)3);

		// check BZIP2 is the second compressor
		assertEquals(lzcompressor, compressorZero);
	}

	// FIXME add exact decompression check.

//	public void testCompress() throws IOException {
//
//		// do bzip2 compression
//		byte[] compressedData = doCompress(UNCOMPRESSED_DATA_1.getBytes());
//
//		// output size same as expected?
//		//assertEquals(compressedData.length, COMPRESSED_DATA_1.length);
//
//		// check each byte is exactly as expected
//		for (int i = 0; i < compressedData.length; i++) {
//			assertEquals(COMPRESSED_DATA_1[i], compressedData[i]);
//		}
//	}
//
//	public void testBucketDecompress() throws IOException {
//
//		byte[] compressedData = COMPRESSED_DATA_1;
//
//		// do bzip2 decompression with buckets
//		byte[] uncompressedData = doBucketDecompress(compressedData);
//
//		// is the (round-tripped) uncompressed string the same as the original?
//		String uncompressedString = new String(uncompressedData);
//		assertEquals(uncompressedString, UNCOMPRESSED_DATA_1);
//	}
//
	public void testByteArrayDecompress() throws IOException {

        // build 5k array
		byte[] originalUncompressedData = new byte[5 * 1024];
		for(int i = 0; i < originalUncompressedData.length; i++) {
			originalUncompressedData[i] = 1;
		}

		byte[] compressedData = doCompress(originalUncompressedData);
		byte[] outUncompressedData = new byte[5 * 1024];

		int writtenBytes = 0;

		writtenBytes = Compressor.COMPRESSOR_TYPE.LZMA_NEW.decompress(compressedData, 0, compressedData.length, outUncompressedData);

		assertEquals(writtenBytes, originalUncompressedData.length);
		assertEquals(originalUncompressedData.length, outUncompressedData.length);

        // check each byte is exactly as expected
		for (int i = 0; i < outUncompressedData.length; i++) {
			assertEquals(originalUncompressedData[i], outUncompressedData[i]);
		}
	}

	public void testRandomByteArrayDecompress() throws IOException {

		Random random = new Random(1234);

		for(int rounds=0;rounds<100;rounds++) {
			int scale = random.nextInt(19) + 1;
			int size = random.nextInt(1 << scale);

			// build 5k array
			byte[] originalUncompressedData = new byte[size];
			random.nextBytes(originalUncompressedData);

			byte[] compressedData = doCompress(originalUncompressedData);
			byte[] outUncompressedData = new byte[size];

			int writtenBytes = 0;

			writtenBytes = Compressor.COMPRESSOR_TYPE.LZMA_NEW.decompress(compressedData, 0, compressedData.length, outUncompressedData);

			assertEquals(writtenBytes, originalUncompressedData.length);
			assertEquals(originalUncompressedData.length, outUncompressedData.length);

			// check each byte is exactly as expected
			for (int i = 0; i < outUncompressedData.length; i++) {
				assertEquals(originalUncompressedData[i], outUncompressedData[i]);
			}
		}
	}

	public void testCompressException() throws IOException {

		byte[] uncompressedData = UNCOMPRESSED_DATA_1.getBytes();
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();

		try {
			Compressor.COMPRESSOR_TYPE.LZMA_NEW.compress(inBucket, factory, 32, 32);
		} catch (CompressionOutputSizeException e) {
			// expect this
			return;
		}
		// TODO LOW codec doesn't actually enforce size limit
		//fail("did not throw expected CompressionOutputSizeException");
	}

	public void testDecompressException() throws IOException {

		// build 5k array
		byte[] uncompressedData = new byte[5 * 1024];
		for(int i = 0; i < uncompressedData.length; i++) {
			uncompressedData[i] = 1;
		}

		byte[] compressedData = doCompress(uncompressedData);

		Bucket inBucket = new ArrayBucket(compressedData);
		NullBucket outBucket = new NullBucket();
		InputStream decompressorInput = null;
		OutputStream decompressorOutput = null;

		try {
			decompressorInput = inBucket.getInputStream();
			decompressorOutput = outBucket.getOutputStream();
			Compressor.COMPRESSOR_TYPE.LZMA_NEW.decompress(decompressorInput, decompressorOutput, 4096 + 10, 4096 + 20);
			decompressorInput.close();
			decompressorOutput.close();
		} catch (CompressionOutputSizeException e) {
			// expect this
			return;
		} finally {
			Closer.close(decompressorInput);
			Closer.close(decompressorOutput);
			inBucket.free();
			outBucket.free();
		}
		// TODO LOW codec doesn't actually enforce size limit
		//fail("did not throw expected CompressionOutputSizeException");
	}

	private byte[] doBucketDecompress(byte[] compressedData) throws IOException {

		Bucket inBucket = new ArrayBucket(compressedData);
		Bucket outBucket = new ArrayBucket();
		InputStream decompressorInput = null;
		OutputStream decompressorOutput = null;

		try {
			decompressorInput = inBucket.getInputStream();
			decompressorOutput = outBucket.getOutputStream();
			Compressor.COMPRESSOR_TYPE.LZMA_NEW.decompress(decompressorInput, decompressorOutput, 32768, 32768 * 2);
			decompressorInput.close();
			decompressorOutput.close();
		} finally {
			Closer.close(decompressorInput);
			Closer.close(decompressorOutput);
			inBucket.free();
		}

		InputStream in = null;

		in = outBucket.getInputStream();
		long size = outBucket.size();
		byte[] outBuf = new byte[(int) size];

		in.read(outBuf);

		return outBuf;
	}

	private byte[] doCompress(byte[] uncompressedData) throws IOException {
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();
		Bucket outBucket = null;

		outBucket = Compressor.COMPRESSOR_TYPE.LZMA_NEW.compress(inBucket, factory, 32768, 32768);

		InputStream in = null;
		in = outBucket.getInputStream();
		long size = outBucket.size();
		byte[] outBuf = new byte[(int) size];

		in.read(outBuf);

		return outBuf;
	}
}
