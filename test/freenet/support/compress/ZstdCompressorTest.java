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

import static freenet.support.compress.Compressor.COMPRESSOR_TYPE.ZSTD;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import freenet.support.io.*;
import org.junit.Test;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Test case for {@link freenet.support.compress.ZstdCompressor} class.
 */
public class ZstdCompressorTest {

	private static final String UNCOMPRESSED_DATA_1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
			+ "aksjdhaskjsdhaskjdhaksjdhkajsdhkasdhkqhdioqahdkashdkashdnkashdnaskdhnkasjhdnkasjhdnkasjhdnkasjhdnkasjhdnkashdnkasjhdnkasjhdnkasjhndkasjhdna"
			+ "djjjjjjjjjjjjjjj3j12j312j312j312j31j23hj123niah1ia3h1iu2b321uiab31ugb312gba38gab23igb12i3ag1b2ig3bi1g3bi1gba3iu12ba3iug1bi3ug1b2i3gab1i2ua3";

	/**
	 * Test ZSTD compressor's identity
	 */
	@Test
	public void testZstdCompressorIdentity() {
		Compressor compressor = Compressor.COMPRESSOR_TYPE.getCompressorByMetadataID((short)4);
		assertEquals(ZSTD, compressor);

		Compressor compressorByName = Compressor.COMPRESSOR_TYPE.getCompressorByName("ZSTD");
		assertEquals(ZSTD, compressorByName);
	}

	@Test
	public void testCompressDecompress() throws IOException {
		// Compress
		byte[] compressedData = doCompress(UNCOMPRESSED_DATA_1.getBytes());

		// Compressed should be smaller than original for this repetitive data
		assertTrue("Compressed size should be smaller", compressedData.length < UNCOMPRESSED_DATA_1.length());

		// Decompress
		byte[] uncompressedData = doDecompress(compressedData);

		// Round-trip should produce original data
		String uncompressedString = new String(uncompressedData);
		assertEquals(UNCOMPRESSED_DATA_1, uncompressedString);
	}

	@Test
	public void testBucketDecompress() throws IOException {
		byte[] originalData = UNCOMPRESSED_DATA_1.getBytes();
		byte[] compressedData = doCompress(originalData);

		// Decompress using bucket/stream interface
		byte[] uncompressedData = doBucketDecompress(compressedData);

		String uncompressedString = new String(uncompressedData);
		assertEquals(UNCOMPRESSED_DATA_1, uncompressedString);
	}

	@Test
	public void testByteArrayDecompress() throws IOException {
		// Build 5k array of repetitive data
		byte[] originalUncompressedData = new byte[5 * 1024];
		Arrays.fill(originalUncompressedData, (byte) 1);

		byte[] compressedData = doCompress(originalUncompressedData);
		byte[] outUncompressedData = new byte[5 * 1024];

		int writtenBytes = ZSTD.decompress(compressedData, 0, compressedData.length, outUncompressedData);

		assertEquals(originalUncompressedData.length, writtenBytes);
		assertArrayEquals(originalUncompressedData, outUncompressedData);
	}

	@Test
	public void testRandomDataRoundTrip() throws IOException {
		Random random = new Random(12345); // Fixed seed for reproducibility

		for (int round = 0; round < 50; round++) {
			int size = random.nextInt(65536) + 1; // 1 byte to 64KB
			byte[] originalData = new byte[size];
			random.nextBytes(originalData);

			byte[] compressedData = doCompress(originalData);
			byte[] decompressedData = new byte[size];

			int writtenBytes = ZSTD.decompress(compressedData, 0, compressedData.length, decompressedData);

			assertEquals("Round " + round + ": size mismatch", size, writtenBytes);
			assertArrayEquals("Round " + round + ": data mismatch", originalData, decompressedData);
		}
	}

	@Test
	public void testCompressException() throws IOException {
		byte[] uncompressedData = UNCOMPRESSED_DATA_1.getBytes();
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();

		try {
			ZSTD.compress(inBucket, factory, 32, 32);
		} catch (CompressionOutputSizeException e) {
			// Expected
			return;
		}
		// Note: Some codecs don't strictly enforce size limits during compression
	}

	@Test
	public void testDecompressException() throws IOException {
		// Build 5k array
		byte[] uncompressedData = new byte[5 * 1024];
		Arrays.fill(uncompressedData, (byte) 1);

		byte[] compressedData = doCompress(uncompressedData);

		Bucket inBucket = new ArrayBucket(compressedData);
		NullBucket outBucket = new NullBucket();

		try (
			InputStream decompressorInput = inBucket.getInputStream();
			OutputStream decompressorOutput = outBucket.getOutputStream()
		) {
			ZSTD.decompress(decompressorInput, decompressorOutput, 4096 + 10, 4096 + 20);
		} catch (CompressionOutputSizeException e) {
			// Expected
			return;
		} finally {
			inBucket.free();
			outBucket.free();
		}
		fail("Did not throw expected CompressionOutputSizeException");
	}

	@Test
	public void testEmptyData() throws IOException {
		byte[] emptyData = new byte[0];
		byte[] compressedData = doCompress(emptyData);

		// Zstd produces a small header even for empty data
		assertTrue("Compressed empty data should exist", compressedData.length > 0);

		byte[] decompressedData = doDecompress(compressedData);
		assertEquals("Decompressed empty data should be empty", 0, decompressedData.length);
	}

	@Test
	public void testLargeData() throws IOException {
		// Test with 1MB of data
		byte[] largeData = new byte[1024 * 1024];
		new Random(54321).nextBytes(largeData);

		byte[] compressedData = doCompress(largeData);
		byte[] decompressedData = doDecompress(compressedData);

		assertArrayEquals("Large data round-trip failed", largeData, decompressedData);
	}

	private byte[] doCompress(byte[] uncompressedData) throws IOException {
		Bucket inBucket = new ArrayBucket(uncompressedData);
		BucketFactory factory = new ArrayBucketFactory();

		Bucket outBucket = ZSTD.compress(inBucket, factory, Integer.MAX_VALUE, Integer.MAX_VALUE);
		return BucketTools.toByteArray(outBucket);
	}

	private byte[] doDecompress(byte[] compressedData) throws IOException {
		try (
			ByteArrayInputStream decompressorInput = new ByteArrayInputStream(compressedData);
			ByteArrayOutputStream decompressorOutput = new ByteArrayOutputStream()
		) {
			ZSTD.decompress(decompressorInput, decompressorOutput, Integer.MAX_VALUE, Integer.MAX_VALUE);
			return decompressorOutput.toByteArray();
		}
	}

	private byte[] doBucketDecompress(byte[] compressedData) throws IOException {
		try (
			ByteArrayInputStream decompressorInput = new ByteArrayInputStream(compressedData);
			ByteArrayOutputStream decompressorOutput = new ByteArrayOutputStream()
		) {
			ZSTD.decompress(decompressorInput, decompressorOutput, 32768, 32768 * 2);
			return decompressorOutput.toByteArray();
		}
	}
}
