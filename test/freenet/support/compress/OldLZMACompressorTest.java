/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import junit.framework.TestCase;

import freenet.keys.ClientSSKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.NullBucket;

/**
 * Test case for {@link Bzip2Compressor} class.
 */
public class OldLZMACompressorTest extends TestCase {

	private static final String UNCOMPRESSED_DATA_1 = GzipCompressorTest.UNCOMPRESSED_DATA_1;
	private static final byte[] COMPRESSED_DATA_1_LZMA_OLD = new byte[]{ 0 , 48 ,
			-18 , 72 , 98 , -36 , 19 , 25 , -16 , -32 , 77 , 96 , -112
			, 117 , -91 , 80 , -127 , 51 , 102 , -111 , -56 , -10 , -9
			, -8 , 69 , 94 , -50 , -109 , -81 , 112 , -1 , 71 , 89 ,
			-8 , 119 , 118 , -37 , -54 , 22 , 86 , 121 , -40 , -113 ,
			-120 , 125 , 77 , -28 , -54 , -82 , 124 , -78 , 126 , 17 ,
			28 , 72 , -70 , 43 , -40 , -75 , -13 , -1 , 34 , 90 , -106
			, 73 , 55 , -114 , -107 , 61 , -24 , 73 , 98 , -101 , 120
			, 81 , 83 , -37 , 107 , -16 , -11 , 82 , -79 , -95 , -6 ,
			44 , -22 , -65 , 93 , -76 , 70 , -30 , -3 , 19 , 13 , 32 ,
			119 , -71 , 32 , 65 , 4 , 4 , 34 , 6 , 73 , 22 , -67 , -17
			, 18 , 6 , -30 , -95 , 53 , -1 , -91 , -20 , -23 , -93 ,
			12 , 25 , 94 , 52 , -45 , 42 , -45 , -98 , -80 , -4 , 113
			, 108 , 59 , 33 , 15 , -18 , -42 , -87 , -60 , -121 , -33
			, 92 , 1 , 124 , 10 , 113 , -69 , 32 , -107 , 126 , 44 ,
			-38 , -3 , -72 , 22 , -64 };

	/**
	 * test BZIP2 compressor's identity and functionality
	 */
	public void testOldLzmaCompressor() throws IOException {
		Compressor.COMPRESSOR_TYPE lzcompressor = Compressor.COMPRESSOR_TYPE.LZMA;
		Compressor compressorZero = Compressor.COMPRESSOR_TYPE.getCompressorByMetadataID((short)2);
		assertEquals(lzcompressor, compressorZero);
	}

	public void testCanUncompressKeyCompressedWithOldLzma() throws Exception {
		byte[] data = (UNCOMPRESSED_DATA_1
				+ UNCOMPRESSED_DATA_1
				+ UNCOMPRESSED_DATA_1
				+ UNCOMPRESSED_DATA_1
				+ UNCOMPRESSED_DATA_1
				+ UNCOMPRESSED_DATA_1).getBytes("UTF-8");
		// use static inline data, created with the following commented code.
		// DummyRandomSource random = new DummyRandomSource();
		// InsertableClientSSK ik = InsertableClientSSK.createRandom(random, "foo");
		// SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(data);
		// ClientSSKBlock clientSskBlock = ik.encode(
		// 		bucket,
		// 		false,
		// 		false,
		// 		(short) -1,
		// 		bucket.size(),
		// 		random,
		// 		COMPRESSOR_TYPE.LZMA.name);
		// FreenetURI clientUri = ik.getInsertURI();
		// byte[] rawBlockData = clientSskBlock.getBlock().getRawData();
		// byte[] rawBlockHeaders = clientSskBlock.getBlock().getRawHeaders();

		FreenetURI clientUri = new FreenetURI(
				"SSK@AJrVygeiL9L1kQwaREMSSTIZSBoMKz2sok-d5rHG~LHd,94rxy87rAIFvF2xN0Rd6NXtwHROaneJ83ZC965wPcb4,AQECAAE/foo");
		byte[] rawBlockData = new byte[]{106, 126, -72, 5, 53, -25, -72 , 56,
				125, -96, -19, -124, -16, -59, -8, -46, -43, -36, -84 , 30, -93, 29,
				-36, -92, 123, -117, -78, 114, 118, 28, 71, -128, -58, -89, 68, -26,
				-7, -14, 94, -28, -106, -65, -113 , -15, -128, -45, 99, -71, -3, 83,
				58, 79, 11, 62, 13, -62 , 31, 16, 0, -44, 25, 81, 117, -56, 44, 99,
				93, 30, -105, 43, -49, 76, 3, 112, -53, 17, -5, -95, -102, -24, 109,
				28 , -116, -37, -2, 116, 6, 50, -76, -114, 92, -69, 68, 8, 126, -48,
				-15, -17, -4, 66, 47, -11, -19, 1, -80, 38, -72 , 117, 17, 7, 67, -40,
				-77, 26, 15, 18, 108, 75, 122, 26, 62, 121, 101, 13, -8, 16, -52, 48,
				83, 15, 44, -75, -83, 10, 18, -78, 64, -88, -3, 83, 40, 23, 105, 110,
				-52, -57, -22, -36, 45, 59, -14, 103, 50, 38, 22, -12, -91, 64, -101 ,
				12, -65, -3, 112, 0, -31, 65, -77, 2, -52, 10, 79, 42, -65, 18, -90,
				-116, 14, -124, -108, 74, -16, 68, 31, 41, -16, 3, 97, -37, 28, -36,
				-3, -3, 114, 68, -112, 56, 16, 95, 106, -42, 74, -18, -42, 75, 78, 10,
				-106, 68, 23, 118 , -41, -41, 120, 112, -112, 112, 54, 60, 25, 85,
				109, 85, 22, -33, -98, 34, 3, 11, -121, -103, -66, -27, 6, 9, 101, 94,
				83, 28, -53, 92, 24, -42, -7, -51, -107, 14, 121, 121 , -22, 116,
				-128, 17, -64, 36, -100, 3, 26, -18, -57, -70, 4, -52, -103, -108, 80,
				-83, 63, 107, 103, 67, -116, 14, -115, -108, 39, -126, 61, 53, -126,
				58, -106, 42, -7, -36, -59, 98, -111, -85, -45, 110, 117, 73, 87, -16,
				-34, 1, 91 , -92, -74, 100, -93, 105, -65, -80, -117, 105, -13, 99,
				-96, -54, 1, -25, -107, -124, 67, 93, -82, -107, -114, -8, 100, -26,
				-87, -70, 59, -20, -121, -47, -111, -10, 31, -84 , 98, 49, -26, -31,
				-92, 40, 109, -87, -7, 49, 61, 88, 100 , 120, -60, -71, -113, -62,
				-45, -1, 53, -35, 62, -89, 127 , 115, -4, -30, -59, 8, 126, -9, 111,
				-80, 91, -127, -65, 117, 115, 11, 63, -68, -111, -59, -112, -105, -14,
				-72, -16 , 83, -79, -112, -42, -52, -102, 113, -124, -48, 53, -11,
				-62, 14, -1, 116, 64, -1, 15, 124, -93, 61, -79, -90, 81, 117, -113,
				55, 76, -75, -118, -41, -112, 28, 8, -2, -120, -100, -85, -61, -78,
				75, -47, -89, 118, 50, 32, 123, -96, 77, 103, 103, -27, 58, -4, -113,
				-8, 12, 44, 57, 33, -30, -40, 15, -52, 40, -30, 65, 25, 25, 72, -93,
				112, 26, -95, 17, 99, -90, -14, 22, -70, -118, 117, -14, -68, -22,
				-20, -74, -128, -108, 88, 25, 75, -59, -69, -78, -5, 124, -53, -118,
				127, -94, 24, -103, 41, -8, -56, -12, -29, 96, -97, 4, 26, -49, 31,
				-59, 60, 1, -46, 57, -40, -103, -32, -3, -71, -34, -11, -122, 25, 17,
				-2, 54, -63, -33, -25, 75, 114, 26, 42, 88, 81, -42, 95, 94, -63, 101,
				-79, -79, -59 , 21, 16, -42, -78, -67, 67, 58, 67, -34, 62, -100, -52,
				-79, -53, 78, 16, -115, -79, -74, 90, 70, -49, 38, 33, -91 , 104, 95,
				46, 113, 120, -46, 19, -42, -41, -119, -114, -62 , 29, 1, -108, -36,
				-28, 110, 86, 62, 45, 71, -41, -27, -109, 93, 4, 94, 95, -60, 87,
				-111, 16, -84, 115, 75, 21, 57, 39, -86, -61, -119, 23, -109, 33,
				-122, -72, -76, -66, -117, 49, 39, -31, -2, 49, 103, 110, 36, -43, 32,
				-87, 113 , -4, 90, 26, 45, -45, -118, -58, 45, -72, -50, -47, 30, 113,
				-62, -20, -122, 64, 89, 98, -58, -119, -11, 60, 46, 82, 37, -81, 80,
				-63, -43, 29, -105, 26, 107, -77, -21, -5 , -68, -78, -71, -45, 17,
				-26, 120, 124, 95, 59, 38, 60, 113, 101, 88, 91, -119, -70, 56, 19,
				34, 68, -80, -84, -57 , -61, -13, 29, -104, -85, -111, 60, -13, 115,
				-6, 77, -79 , -34, -19, -12, -127, -4, 77, 88, -21, 16, 27, 23, 100,
				-10, 32, -78, 75, 77, 30, -10, 5, 65, -51, -106, 68, -60, -80, 38,
				113, -128, 43, 124, -69, -116, -128, -124, -81, -28, 81, -2, 11, 14,
				-124, -77, 38, -95, -71, -46, -109, 48, -78, 105, -62, -68, -101, 12,
				119, -87, -112, 40, 77, -109, -71, -5, -11, 104, 79, 95, 0, -5, 80,
				121, 3, -99, 79, 56, -93, 34, -35, 95, -99, -25, -43, 36, 110, -61,
				-83 , -45, -125, 90, 19, -97, 80, -76, 41, -58, 22, -2, 49, 122, 90,
				-114, 91, -40, -76, 15, 47, 78, 105, -79, 123, 114, -32, -4, -63,
				-105, 119, 2, 1, 12, -48, 88, -79, -120 , 65, -48, 4, 43, -127, -20,
				30, -30, 64, 29, 12, -126, -42, -42, -114, -62, 49, -48, -53, -47,
				-29, -14, -71, -9, 13, -46, -111, 59, 107, -38, 55, 22, -121, -60, 34,
				53, 23 , 11, 127, -34, -48, -19, -118, 63, 25, -65, 29, 123, -124 ,
				-16, -100, -99, 96, 104, -8, 107, 68, -5, 63, -98, 119, -61, -117,
				-38, 46, -46, -10, -22, -90, -97, -88, 7, 39, -2, -106, 27, -97, -37,
				-69, -54, -9, 44, 110, 43, -10, 42 , -73, 102, -58, -74, -12, -22, 90,
				-20, 6, -111, 84, 57, -88, -14, -89, 53, 126, 30, 63, -9, 25, -67, 3,
				26, -39, -112, -90, -127, 60, 33, -40, 102, 77, -56, -109, -97, 14,
				58, -50, 107, 44, 101, -95, -119, -86, 41, -32, 20, 0, -77 , 73, -119,
				106, -30, -85, 70, -88, -58, 89, 32, 122, -33, 24, -25, -45, 50, -17,
				78, 40, 19, -61, 5, -110, -36, 88, -116, -93, 88, 112, 11, -102, 99,
				97, -86, -103, 47, -68, -32, -65, 42, 78, -20, -69, 69, -99, -124,
				-14, -127, 72, -57, 108, 37, -103, 42, 124, 89, -36, -33, -78, -123,
				116, 58, -61, 33, -114, 49, 100, 94, -3, -128, -60, 56, 67, -113, -2
		};

		byte[] rawBlockHeaders = new byte[]{0, 1, 0, 2, -26, -118, -62, -124,
				-84, -78, -117, 99, 120, 98, 38, -62, 39, -116, -57, 109, -92, -1,
				-86, 103, 63, -25, -61, 127, -82, 21, -35, 1 , 87, 123, 66, 62, -110,
				-115, 108, 45, -29, -25, -114, -45 , -112, -55, 81, 94, -118, -90,
				-110, 126, 0, 110, 73, 57, -44, 32, 76, -110, -63, -64, 127, 56, -56,
				86, 34, -80, 91 , 54, -12, 0, 51, -120, -119, -34, -128, 123, -114,
				68, 78 , 117, 86, 111, 66, 43, -92, 105, 75, 68, -95, -25, 121, 96,
				-77, 113, -5, 76, -69, -49, 36, -100, -81, 4, 61, -3, -121, 38, -92,
				33, 88, 39, 120, 90, -21, 106, 53, 108, 63 , -63, -26, -44, -89, -48,
				76, -96, -123, 81, -13, 122, 80 , 94, 68, -10, 123, -46 };

		ClientSSKBlock oldClientSSkBlock = new ClientSSKBlock(
				rawBlockData,
				rawBlockHeaders,
				InsertableClientSSK.create(clientUri),
				false);
		byte[] decoded = oldClientSSkBlock.memoryDecode();
		for (int i = 0; i < decoded.length; i++) {
			assertEquals(decoded[i], data[i]);
		}
	}

	public void testCompress() throws IOException, CompressionRatioException {

		// do bzip2 compression
		byte[] compressedData = doCompress(UNCOMPRESSED_DATA_1.getBytes());
		for (int i = 0; i < compressedData.length; i++) {
			assertEquals(COMPRESSED_DATA_1_LZMA_OLD[i], compressedData[i]);
		}

	}

	public void testBucketDecompress() throws IOException {

		byte[] compressedData = COMPRESSED_DATA_1_LZMA_OLD;

		// do bzip2 decompression with buckets
		byte[] uncompressedData = doBucketDecompress(compressedData);

		// is the (round-tripped) uncompressed string the same as the original?
		String uncompressedString = new String(uncompressedData);
		assertEquals(uncompressedString, UNCOMPRESSED_DATA_1);
	}

	public void testByteArrayDecompress() throws IOException, CompressionRatioException {

        // build 5k array
		byte[] originalUncompressedData = new byte[5 * 1024];
		for(int i = 0; i < originalUncompressedData.length; i++) {
			originalUncompressedData[i] = 1;
		}

		byte[] compressedData = doCompress(originalUncompressedData);
		byte[] outUncompressedData = new byte[5 * 1024];

		int writtenBytes = 0;

		writtenBytes = Compressor.COMPRESSOR_TYPE.LZMA.decompress(compressedData, 0, compressedData.length, outUncompressedData);

		assertEquals(writtenBytes, originalUncompressedData.length);
		assertEquals(originalUncompressedData.length, outUncompressedData.length);

        // check each byte is exactly as expected
		for (int i = 0; i < outUncompressedData.length; i++) {
			assertEquals(originalUncompressedData[i], outUncompressedData[i]);
		}
	}

	public void testCompressException() throws IOException {

		byte[] uncompressedData = UNCOMPRESSED_DATA_1.getBytes();
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();

		try {
			Compressor.COMPRESSOR_TYPE.LZMA.compress(inBucket, factory, 32, 32);
		} catch (CompressionOutputSizeException e) {
			// expect this
			return;
		}
		// TODO LOW codec doesn't actually enforce size limit
		//fail("did not throw expected CompressionOutputSizeException");
	}

	public void testDecompressException() throws IOException, CompressionRatioException {

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
			Compressor.COMPRESSOR_TYPE.LZMA.decompress(decompressorInput, decompressorOutput, 4096 + 10, 4096 + 20);
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

	private byte[] doCompress(byte[] uncompressedData) throws IOException, CompressionRatioException {
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();
		Bucket outBucket = null;

		outBucket = Compressor.COMPRESSOR_TYPE.LZMA.compress(inBucket, factory, uncompressedData.length, uncompressedData.length * 2 + 64);

		InputStream in = null;
		in = outBucket.getInputStream();
		long size = outBucket.size();
		byte[] outBuf = new byte[(int) size];

		in.read(outBuf);

		return outBuf;
	}

	private byte[] doBucketDecompress(byte[] compressedData) throws IOException {
		ByteArrayInputStream decompressorInput = new ByteArrayInputStream(compressedData);
		ByteArrayOutputStream decompressorOutput = new ByteArrayOutputStream();

		COMPRESSOR_TYPE.LZMA.decompress(decompressorInput, decompressorOutput, 32768, 32768 * 2);

		byte[] outBuf = decompressorOutput.toByteArray();
		try {
			decompressorInput.close();
			decompressorOutput.close();
		} finally {
			Closer.close(decompressorInput);
			Closer.close(decompressorOutput);
		}

		return outBuf;
	}
}
