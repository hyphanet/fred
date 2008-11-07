package freenet.support;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import junit.framework.TestCase;

public class BloomFilterTest extends TestCase {
	private static final int FILTER_SIZE = 4 * 1024; // MUST be > PASS,
	private static final int PASS = 2048;
	private static final int PASS_REMOVE = 4096;
	private static final int PASS_POS = 256;
	private static final int PASS_FALSE = 8192;

	private final Random rand = new Random(12345);

	private void _testFilterPositive(BloomFilter filter) {
		byte[][] list = new byte[PASS_POS][];

		for (int i = 0; i < PASS_POS; i++) {
			byte[] b = new byte[32];
			rand.nextBytes(b);

			filter.addKey(b);
			list[i] = b;
		}

		for (byte[] b : list) {
			assertTrue(filter.checkFilter(b));
		}
	}

	public void testCountingFilterPositive() {
		int K = BloomFilter.optimialK(FILTER_SIZE, PASS_POS);
		BloomFilter filter = BloomFilter.createFilter(FILTER_SIZE, K, true);
		_testFilterPositive(filter);
	}

	public void testBinaryFilterPositive() {
		int K = BloomFilter.optimialK(FILTER_SIZE, PASS_POS);
		BloomFilter filter = BloomFilter.createFilter(FILTER_SIZE, K, false);
		_testFilterPositive(filter);
	}

	public void testCountingFilterRemove() {
		int K = BloomFilter.optimialK(FILTER_SIZE, PASS);
		BloomFilter filter = BloomFilter.createFilter(FILTER_SIZE, K, true);

		Map<ByteArrayWrapper, byte[]> baseList = new HashMap<ByteArrayWrapper, byte[]>();

		// Add Keys
		for (int i = 0; i < PASS; i++) {
			byte[] b = new byte[32];
			do {
				rand.nextBytes(b);
			} while (baseList.containsKey(new ByteArrayWrapper(b)));

			filter.addKey(b);
			baseList.put(new ByteArrayWrapper(b), b);
			assertTrue("check add BASE", filter.checkFilter(b));
		}

		// Add some FALSE_PASS keys
		Map<ByteArrayWrapper, byte[]> newList = new HashMap<ByteArrayWrapper, byte[]>();
		int fPos = 0;
		for (int i = 0; i < PASS_REMOVE; i++) {
			byte[] b = new byte[64];
			ByteArrayWrapper wrapper;
			do {
				rand.nextBytes(b);
				wrapper = new ByteArrayWrapper(b);
			} while (newList.containsKey(wrapper));

			filter.addKey(b);
			newList.put(wrapper, b);
			assertTrue("check add NEW", filter.checkFilter(b));
		}

		// Remove the "NEW" keys and count false positive
		for (byte[] b : newList.values())
			filter.removeKey(b);
		for (byte[] b : newList.values())
			if (filter.checkFilter(b))
				fPos++;

		// Check if some should were removed
		assertFalse("100% false positive?", fPos == PASS_REMOVE);

		// Check if old keys still here
		for (byte[] b : baseList.values())
			assertTrue("check original", filter.checkFilter(b));
	}

	private void _testFilterFalsePositive(BloomFilter filter) {
		Set<ByteArrayWrapper> list = new HashSet<ByteArrayWrapper>();

		// Add Keys
		for (int i = 0; i < PASS; i++) {
			byte[] b = new byte[32];
			do {
				rand.nextBytes(b);
			} while (list.contains(new ByteArrayWrapper(b)));

			filter.addKey(b);
			list.add(new ByteArrayWrapper(b));
			assertTrue("check add", filter.checkFilter(b));
		}

		System.out.println("---" + filter + "---");

		int fPos = 0;
		for (int i = 0; i < PASS_FALSE; i++) {
			byte[] b = new byte[64]; // 64 bytes, sure not exist
			rand.nextBytes(b);

			if (filter.checkFilter(b))
				fPos++;
		}

		final int K = filter.getK();
		final double q = 1 - Math.pow(1 - 1.0 / FILTER_SIZE, K * PASS);
		final double p = Math.pow(q, K);
		final double actual = (double) fPos / PASS_FALSE;
		final double limit = p * 1.05 + 1.0 / PASS_FALSE;

		//*-
		System.out.println("          k = " + K);
		System.out.println("          q = " + q);
		System.out.println("          p = " + p);
		System.out.println("      limit = " + limit);
		System.out.println("     actual = " + actual);
		System.out.println(" actual / p = " + actual / p);
		/**/

		assertFalse("false positive, p=" + p + ", actual=" + actual, actual > limit);
	}

	public void testCountingFilterFalsePositive() {
		int K = BloomFilter.optimialK(FILTER_SIZE, PASS);
		BloomFilter filter = BloomFilter.createFilter(FILTER_SIZE, K, true);
		_testFilterFalsePositive(filter);
	}

	public void testBinaryFilterFalsePositive() {
		int K = BloomFilter.optimialK(FILTER_SIZE, PASS);
		BloomFilter filter = BloomFilter.createFilter(FILTER_SIZE, K, false);
		_testFilterFalsePositive(filter);
	}
}
