/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.File;
import java.io.FileWriter;
import org.spaceroots.mantissa.random.ScalarSampleStatistics;

import junit.framework.*;

public class YarrowTest extends TestCase {

	private static final String SEED_FILE_NAME = "prng-test.seed";
	private static final File SEED_FILE = new File(SEED_FILE_NAME);
	private static final int SEED_SIZE = 624;
	private static final byte[] SEED_OUTPUT_YARROW_FILE = new byte[]{
		(byte)0xEE, (byte)0x9E, (byte)0xE2, (byte)0x3B, (byte)0x8D,
		(byte)0x1B, (byte)0x97, (byte)0xED, (byte)0x68, (byte)0x40,
		(byte)0x1F, (byte)0xBD, (byte)0x91, (byte)0xEA, (byte)0xA2,
		(byte)0xCD, (byte)0xD0, (byte)0xEB, (byte)0x37, (byte)0xF4 
	};
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		FileWriter fw = new FileWriter(SEED_FILE);
		for(int i = 0; i < 256; i++)
			fw.write(i);
		fw.flush();
		fw.close();
	}

	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		assertTrue(SEED_FILE.delete());
	}

//	public void testConsistencySeedFromFile() throws NoSuchAlgorithmException, UnsupportedEncodingException {
//		Yarrow y = new Yarrow(SEED_FILE, "SHA1", "Rijndael", false, false, false);
//		MessageDigest md = MessageDigest.getInstance("SHA-1");
//
//		byte[] bytes = new byte[SEED_SIZE];
//
//		y.nextBytes(bytes);
//		md.update(bytes);
//
//		bytes = md.digest();
//		assertEquals(new String(bytes, "UTF-8"), new String(SEED_OUTPUT_YARROW_FILE, "UTF-8"));
//	}

	public void testDouble() {
		Yarrow y = new Yarrow(SEED_FILE, "SHA1", "Rijndael", false, false, false);
		ScalarSampleStatistics sample = new ScalarSampleStatistics();

		for(int i = 0; i < 10000; ++i) {
			sample.add(y.nextDouble());
		}

		assertEquals(0.5, sample.getMean(), 0.02);
		assertEquals(1.0 / (2.0 * Math.sqrt(3.0)), sample.getStandardDeviation(), 0.002);
	}

	public void testNextInt() {
//		Yarrow y = new Yarrow(SEED_FILE, "SHA1", "Rijndael", false, false, false);
//		for(int n = 1; n < 20; ++n) {
//			int[] count = new int[n];
//			for(int k = 0; k < 10000; ++k) {
//				int l = y.nextInt(n);
//				++count[l];
//				assertTrue(l >= 0);
//				assertTrue(l < n);
//			}
//			for(int i = 0; i < n; ++i) {
//				assertTrue(n * count[i] > 8800);
//				assertTrue(n * count[i] < 11100);
//			}
//		}
	}
	
	public void testNextBoolean() {
		Yarrow y = new Yarrow(SEED_FILE, "SHA1", "Rijndael", false, false, false);
		int[] results = new int[2];
		int RUNS = 1000000;
		for(int i=0; i<RUNS; i++) {
			if(y.nextBoolean())
				results[0]++;
			else
				results[1]++;
		}

		assertEquals(RUNS, results[0]+results[1]);
		assertTrue(results[0] > RUNS/2 - RUNS/1000);
		assertTrue(results[1] > RUNS/2 - RUNS/1000);
	}
}
