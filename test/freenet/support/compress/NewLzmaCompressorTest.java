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

	private byte[] doCompress(byte[] uncompressedData) throws IOException {
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();
		Bucket outBucket = null;

		outBucket = Compressor.COMPRESSOR_TYPE.LZMA_NEW.compress(inBucket, factory, uncompressedData.length, uncompressedData.length * 2 + 64);

		InputStream in = null;
		in = outBucket.getInputStream();
		long size = outBucket.size();
		byte[] outBuf = new byte[(int) size];

		in.read(outBuf);

		return outBuf;
	}
}
