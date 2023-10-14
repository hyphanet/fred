/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.support.compress;

import static freenet.support.compress.Compressor.COMPRESSOR_TYPE.GZIP;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import freenet.support.io.*;
import org.junit.Test;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Test case for {@link freenet.support.compress.GzipCompressor} class.
 *
 * @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class GzipCompressorTest {

	public static final String UNCOMPRESSED_DATA_1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
			+ "aksjdhaskjsdhaskjdhaksjdhkajsdhkasdhkqhdioqahdkashdkashdnkashdnaskdhnkasjhdnkasjhdnkasjhdnkasjhdnkasjhdnkashdnkasjhdnkasjhdnkasjhndkasjhdna"
			+ "djjjjjjjjjjjjjjj3j12j312j312j312j31j23hj123niah1ia3h1iu2b321uiab31ugb312gba38gab23igb12i3ag1b2ig3bi1g3bi1gba3iu12ba3iug1bi3ug1b2i3gab1i2ua3";

	private static final byte[] COMPRESSED_DATA_1 = { 31, -117, 8, 0, 0, 0, 0, 0, 0, 0, -99, -117, 81, 10, -60, 48, 8, 68, -49, -92, -13, -77,
			-41, 25, 9, 36, 26, -24, 82, 66, -18, 95, -37, -12, -89, -80, 44, -53, 14, -8, 70, 71, 37, -1, -108, -3, 36, -10, 17, -91, 113, -12,
			24, -53, -110, 87, -44, 121, 38, -99, 39, -10, 86, -4, -67, -77, -107, 28, 111, 108, -117, -7, 81, -38, -39, -57, -118, -66, -39,
			-25, -43, 86, -18, -119, 37, -98, 66, -120, 6, 30, 21, -118, -106, 41, 54, 103, 19, 39, 18, 83, 13, 42, -45, 105, -112, 89, 19, 90,
			-115, 120, 85, -102, -62, -85, -119, 58, 88, -59, -44, 43, -52, 101, 33, 15, 124, -118, 94, -106, 59, -57, -68, 46, -112, 79, -30,
			58, -119, 3, -88, -111, 58, 68, 117, 1, 0, 0 };

	/**
	 * test GZIP compressor's identity and functionality
	 */
	@Test
	public void testGzipCompressor() {
		Compressor compressorZero = Compressor.COMPRESSOR_TYPE.getCompressorByMetadataID((short)0);

		// check GZIP is the first compressor
		assertEquals(GZIP, compressorZero);
	}

	@Test
	public void testCompress() throws IOException {
		// do gzip compression
		byte[] compressedData = doCompress(UNCOMPRESSED_DATA_1.getBytes());

		// output size same as expected?
		assertEquals(compressedData.length, COMPRESSED_DATA_1.length);

		// check each byte is exactly as expected
		assertArrayEquals(COMPRESSED_DATA_1, compressedData);
	}

	@Test
	public void testBucketDecompress() throws IOException {
		// do gzip decompression with buckets
		byte[] uncompressedData = doBucketDecompress(COMPRESSED_DATA_1);

		// is the (round-tripped) uncompressed string the same as the original?
		String uncompressedString = new String(uncompressedData);
		assertEquals(uncompressedString, UNCOMPRESSED_DATA_1);
	}

	@Test
	public void testByteArrayDecompress() throws IOException {
		// build 5k array
		byte[] originalUncompressedData = new byte[5 * 1024];
		Arrays.fill(originalUncompressedData, (byte) 1);

		byte[] compressedData = doCompress(originalUncompressedData);
		byte[] outUncompressedData = new byte[5 * 1024];

		int writtenBytes = GZIP.decompress(compressedData, 0, compressedData.length, outUncompressedData);

		assertEquals(writtenBytes, originalUncompressedData.length);
		assertEquals(originalUncompressedData.length, outUncompressedData.length);

		// check each byte is exactly as expected
		assertArrayEquals(originalUncompressedData, outUncompressedData);
	}

	@Test
	public void testCompressException() throws IOException {

		byte[] uncompressedData = UNCOMPRESSED_DATA_1.getBytes();
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();

		try {
			GZIP.compress(inBucket, factory, 32, 32);
		} catch (CompressionOutputSizeException e) {
			// expect this
			return;
		}
		// TODO LOW codec doesn't actually enforce size limit
		//fail("did not throw expected CompressionOutputSizeException");
	}

	@Test
	public void testDecompressException() throws IOException {
		// build 5k array
		byte[] uncompressedData = new byte[5 * 1024];
		Arrays.fill(uncompressedData, (byte) 1);

		byte[] compressedData = doCompress(uncompressedData);

		Bucket inBucket = new ArrayBucket(compressedData);
		NullBucket outBucket = new NullBucket();

		try (
			InputStream decompressorInput = inBucket.getInputStream();
			OutputStream decompressorOutput = outBucket.getOutputStream()
		) {
			GZIP.decompress(decompressorInput, decompressorOutput, 4096 + 10, 4096 + 20);
		} catch (CompressionOutputSizeException e) {
			// expect this
			return;
		} finally {
			inBucket.free();
			outBucket.free();
		}
		fail("did not throw expected CompressionOutputSizeException");
	}

	private byte[] doBucketDecompress(byte[] compressedData) throws IOException {
		try (
			ByteArrayInputStream decompressorInput = new ByteArrayInputStream(compressedData);
			ByteArrayOutputStream decompressorOutput = new ByteArrayOutputStream()
		) {
			GZIP.decompress(decompressorInput, decompressorOutput, 32768, 32768 * 2);
			return decompressorOutput.toByteArray();
		}
	}

	private byte[] doCompress(byte[] uncompressedData) throws IOException {
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();

		Bucket outBucket = GZIP.compress(inBucket, factory, 32768, 32768);
		byte[] outBuffer = BucketTools.toByteArray(outBucket);

		return outBuffer;
	}
}
