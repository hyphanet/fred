/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.Iterator;

import junit.framework.TestCase;

public class SparseBitmapTest extends TestCase {
	public void testAdd() {
		SparseBitmap s = new SparseBitmap();

		s.add(0, 1);
		assertTrue("Didn't contain 0->1 after adding range 0->1", s.contains(0, 1));
		assertFalse("Contained 2 after adding range 0->1", s.contains(2, 2));

		s.add(3,3);
		assertFalse(s.contains(2, 2));
		assertTrue(s.contains(3, 3));
		assertFalse(s.contains(4, 4));

		s.add(0, 5);
		assertTrue(s.contains(0, 5));

		s.add(10, 15);
		assertTrue(s.contains(0, 5));
		assertTrue(s.contains(10, 15));

		try {
			s.add(5, 0);
			fail("Didn't throw when adding range 5->0");
		} catch (IllegalArgumentException e) {}

		assertTrue(s.contains(0, 5));
		assertTrue(s.contains(10, 15));
	}

	public void testClear() {
		SparseBitmap s = new SparseBitmap();
		s.add(0, 2);
		assertTrue(s.contains(1, 1));
		s.clear();
		assertFalse(s.contains(1, 1));
	}

	public void testRemove() {
		SparseBitmap s = new SparseBitmap();

		s.add(0,4);
		s.add(10, 14);
		assertTrue(s.contains(0, 4));
		assertFalse(s.contains(5, 9));
		assertTrue(s.contains(10, 14));

		//Remove begining of one range
		s.remove(10, 11);
		assertTrue(s.contains(0, 4));
		assertFalse(s.contains(5, 11));
		assertTrue(s.contains(12, 14));

		//Remove end of one range
		s.remove(4, 4);
		assertTrue(s.contains(0, 3));
		assertFalse(s.contains(4, 11));
		assertTrue(s.contains(12, 14));

		//Remove empty range
		s.remove(4,11);
		assertTrue(s.contains(0, 3));
		assertFalse(s.contains(4, 11));
		assertTrue(s.contains(12, 14));

		//Remove from two ranges
		s.remove(3,12);
		assertTrue(s.contains(0, 2));
		assertFalse(s.contains(3, 12));
		assertTrue(s.contains(13, 14));
	}

	public void testContainsThrowsOnBadRange() {
		SparseBitmap s = new SparseBitmap();
		try {
			s.contains(2, 1);
			fail();
		} catch (IllegalArgumentException e) {
			//Expected
		}
	}

	public void testCombineBackwards() {
		SparseBitmap s = new SparseBitmap();
		s.add(5, 10);
		s.add(0, 5);

		Iterator<int[]> it = s.iterator();
		assertTrue(it.hasNext());
		int[] range = it.next();
		assertEquals(0, range[0]);
		assertEquals(10, range[1]);
		assertFalse(it.hasNext());
	}

	public void testCombineMiddle() {
		SparseBitmap s = new SparseBitmap();
		s.add(10, 15);
		s.add(0, 5);
		s.add(5, 10);

		Iterator<int[]> it = s.iterator();
		assertTrue(it.hasNext());
		int[] range = it.next();
		assertEquals(0, range[0]);
		assertEquals(15, range[1]);
		assertFalse(it.hasNext());
	}

	public void testCombineAdjacent() {
		SparseBitmap s = new SparseBitmap();
		s.add(10, 14);
		s.add(0, 4);
		s.add(5, 9);

		Iterator<int[]> it = s.iterator();
		assertTrue(it.hasNext());
		int[] range = it.next();
		assertEquals(0, range[0]);
		assertEquals(14, range[1]);
		assertFalse(it.hasNext());
	}

	public void testIteratorDoubleRemove() {
		SparseBitmap s = new SparseBitmap();
		s.add(1, 2);
		s.remove(0, 3);
	}
}
