package freenet.support;

import java.util.Arrays;

import junit.framework.TestCase;

/**
 * @author sdiz
 */
public class SortedLongSetTest extends TestCase {
	private final static long[] testArray = { 10, 8, 6, 2, 0, 1, 11, Long.MAX_VALUE, 4, 7, 5, 3, Long.MIN_VALUE };

	protected SortedLongSet perpare(long[] array) {
		SortedLongSet set = new SortedLongSet();

		for (int i = 0; i < array.length; i++)
			set.add(array[i]);

		return set;
	}

	public void testGetFirst() {
		// Construction and get
		SortedLongSet set = perpare(testArray);

		assertEquals(Long.MIN_VALUE, set.getFirst());
		assertEquals(Long.MIN_VALUE, set.getFirst()); // make sure not removed
	}

	/**
	 * Test method for {@link freenet.support.SortedLongSet#isEmpty()}.
	 */
	public void testIsEmpty() {
		// Emptiness
		SortedLongSet set1 = perpare(new long[] {});
		assertTrue(set1.isEmpty());

		SortedLongSet set2 = perpare(testArray);
		assertFalse(set2.isEmpty());

		// Remove and isEmpty
		for (int i = 0; i < testArray.length; i++)
			set2.remove(testArray[i]);
		assertTrue(set2.isEmpty());
	}

	/**
	 * Test method for {@link freenet.support.SortedLongSet#contains(long)}.
	 */
	public void testContains() {
		// Construction and get
		SortedLongSet set = perpare(testArray);

		// Contain
		assertTrue(set.contains(0L));
		assertTrue(set.contains(3L));
		assertTrue(set.contains(Long.MAX_VALUE));
		assertTrue(set.contains(Long.MIN_VALUE));

		// Not contain
		assertFalse(set.contains(13L));
		assertFalse(set.contains(-13L));

		// Remove and not contain
		set.remove(0L);
		assertFalse(set.contains(0L));
	}

	/**
	 * Test method for {@link freenet.support.SortedLongSet#remove(long)}.
	 */
	public void testRemove() {
		// Construction and get
		SortedLongSet set = perpare(testArray);

		// Remove
		assertTrue(set.contains(0L));
		set.remove(0L);
		assertFalse(set.contains(0L));

		// Construction and get
		SortedLongSet set2 = perpare(testArray);
		// Remove non exist
		assertFalse(set2.contains(101L));
		set.remove(101L);
		
		// make sure no other element removed
		for (int i = 0; i < testArray.length; i++)
			assertTrue(set2.contains(testArray[i]));
	}

	/**
	 * Test method for {@link freenet.support.SortedLongSet#push(long)}.
	 */
	public void testPush() {
		SortedLongSet set = perpare(testArray);

		assertTrue(set.push(100L));
		assertTrue(set.contains(100L));

		assertFalse(set.push(100L));
	}

	/**
	 * Test method for {@link freenet.support.SortedLongSet#add(long)}.
	 */
	public void testAdd() {
		SortedLongSet set = perpare(testArray);
		set.add(100L);
		assertTrue(set.contains(100L));

		boolean ok = false;
		try {
			set.add(100L);
		} catch (IllegalArgumentException iae) {
			// good
			ok = true;
		}
		assertTrue("exception not thrown", ok);
	}

	/**
	 * Test method for {@link freenet.support.SortedLongSet#removeFirst()}.
	 */
	public void testRemoveFirst() {
		// Construction and get
		SortedLongSet set = perpare(testArray);
		assertEquals(Long.MIN_VALUE, set.removeFirst());
		assertEquals(0L, set.removeFirst());
	}

	/**
	 * Test method for {@link freenet.support.SortedLongSet#clear()}.
	 */
	public void testClear() {
		SortedLongSet set = perpare(testArray);
		assertFalse(set.isEmpty());

		set.clear();

		assertTrue(set.isEmpty());
		assertFalse(set.contains(0L));
	}

	/**
	 * Test method for {@link freenet.support.SortedLongSet#toArray()}.
	 */
	public void testToArray() {
		SortedLongSet set = perpare(testArray);
		
		long[] sortedArray = new long[testArray.length];
		System.arraycopy(testArray, 0, sortedArray, 0, testArray.length);
		Arrays.sort(sortedArray);

		assertTrue(Arrays.equals(sortedArray, set.toArray()));
		
		SortedLongSet set0 =  new SortedLongSet();
		assertTrue(Arrays.equals(new long[]{}, set0.toArray()));
	}
	
	public void testGrownAndScale() {
		SortedLongSet set = new SortedLongSet();

		for (long i = 1; i < 512; i++) {
			set.add(i);
			set.add(-i);
		}

		for (long i = 1; i < 512; i++) {
			assertTrue(set.contains(i));
			assertTrue(set.contains(-i));
		}

		// remove and shink
		for (long i = 1; i < 512; i++) {
			set.remove(i);
			
			assertFalse(set.contains(i));
			assertTrue(set.contains(-i));

			set.remove(-i);
			assertFalse(set.contains(-i));
		}
	}
}
