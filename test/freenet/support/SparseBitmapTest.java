package freenet.support;

import junit.framework.TestCase;

public class SparseBitmapTest extends TestCase {
	public void testAdd() {
		SparseBitmap s = new SparseBitmap();

		s.add(0, 1);
		assertTrue("Didn't contain 0 after adding range 0->1", s.contains(0));
		assertTrue("Didn't contain 1 after adding range 0->1", s.contains(1));
		assertFalse("Contained 2 after adding range 0->1", s.contains(2));

		s.add(3,3);
		assertFalse(s.contains(2));
		assertTrue(s.contains(3));
		assertFalse(s.contains(4));

		s.add(0, 5);
		for(int i = 0; i <= 5; i++) {
			assertTrue(s.contains(i));
		}

		s.add(10, 15);
		for(int i = 0; i <= 5; i++) {
			assertTrue(s.contains(i));
		}
		for(int i = 10; i <= 15; i++) {
			assertTrue(s.contains(i));
		}

		try {
			s.add(5, 0);
			fail("Didn't throw when adding range 5->0");
		} catch (IllegalArgumentException e) {}

		for(int i = 0; i <= 5; i++) {
			assertTrue(s.contains(i));
		}
		for(int i = 10; i <= 15; i++) {
			assertTrue(s.contains(i));
		}
	}

	public void testClear() {
		SparseBitmap s = new SparseBitmap();
		s.add(0, 2);
		assertTrue(s.contains(1));
		s.clear();
		assertFalse(s.contains(1));
	}

	public void testRemove() {
		SparseBitmap s = new SparseBitmap();

		s.add(0,4);
		s.add(10, 14);
		for(int i = 0; i <= 4; i++) {
			assertTrue(s.contains(i));
		}
		for(int i = 5; i <= 9; i++) {
			assertFalse(s.contains(i));
		}
		for(int i = 10; i <= 14; i++) {
			assertTrue(s.contains(i));
		}

		s.remove(4, 4);
		for(int i = 0; i <= 3; i++) {
			assertTrue(s.contains(i));
		}
		for(int i = 4; i <= 9; i++) {
			assertFalse(s.contains(i));
		}
		for(int i = 10; i <= 14; i++) {
			assertTrue(s.contains(i));
		}

		s.remove(4,9);
		for(int i = 0; i <= 3; i++) {
			assertTrue(s.contains(i));
		}
		for(int i = 4; i <= 9; i++) {
			assertFalse(s.contains(i));
		}
		for(int i = 10; i <= 14; i++) {
			assertTrue(s.contains(i));
		}

		s.remove(3,10);
		for(int i = 0; i <= 2; i++) {
			assertTrue(s.contains(i));
		}
		for(int i = 3; i <= 10; i++) {
			assertFalse(s.contains(i));
		}
		for(int i = 11; i <= 14; i++) {
			assertTrue(s.contains(i));
		}
	}
}
