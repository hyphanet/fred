package freenet.support;

import junit.framework.TestCase;

public class TimeSortedHashtableTest extends TestCase {
	public void testAddRemove() {
		TimeSortedHashtable<String> tsh = new TimeSortedHashtable<String>();

		assertFalse(tsh.containsValue("KEY1"));
		assertEquals(0, tsh.countValuesAfter(0));
		assertEquals(0, tsh.size());
		
		tsh.push("KEY1", 100);
		assertEquals(1, tsh.countValuesAfter(0));
		assertEquals(1, tsh.size());
		assertEquals(0, tsh.countValuesAfter(100));
		assertEquals(0, tsh.countValuesAfter(101));
		assertTrue(tsh.containsValue("KEY1"));

		tsh.push("KEY2", 100);
		assertEquals(2, tsh.countValuesAfter(0));
		assertEquals(2, tsh.size());
		assertEquals(0, tsh.countValuesAfter(100));
		assertEquals(0, tsh.countValuesAfter(101));
		assertTrue(tsh.containsValue("KEY1"));
		assertTrue(tsh.containsValue("KEY2"));

		tsh.push("KEY3", 300);
		assertEquals(3, tsh.countValuesAfter(0));
		assertEquals(3, tsh.size());
		assertEquals(1, tsh.countValuesAfter(100));
		assertEquals(1, tsh.countValuesAfter(101));
		assertTrue(tsh.containsValue("KEY1"));
		assertTrue(tsh.containsValue("KEY2"));
		assertTrue(tsh.containsValue("KEY3"));

		tsh.push("KEY1", 200);
		assertEquals(3, tsh.countValuesAfter(0));
		assertEquals(3, tsh.size());
		assertEquals(2, tsh.countValuesAfter(100));
		assertEquals(2, tsh.countValuesAfter(101));
		assertTrue(tsh.containsValue("KEY1"));
		assertTrue(tsh.containsValue("KEY2"));
		assertTrue(tsh.containsValue("KEY3"));
		
		assertTrue(tsh.removeValue("KEY1"));
		assertEquals(2, tsh.countValuesAfter(0));
		assertEquals(2, tsh.size());
		assertEquals(1, tsh.countValuesAfter(100));
		assertEquals(1, tsh.countValuesAfter(101));
		assertFalse(tsh.containsValue("KEY1"));
		assertTrue(tsh.containsValue("KEY2"));
		assertTrue(tsh.containsValue("KEY3"));
		
		tsh.removeBefore(105);
		assertEquals(1, tsh.countValuesAfter(0));
		assertEquals(1, tsh.size());
		assertEquals(1, tsh.countValuesAfter(100));
		assertEquals(1, tsh.countValuesAfter(101));
		assertFalse(tsh.containsValue("KEY1"));
		assertFalse(tsh.containsValue("KEY2"));
		assertTrue(tsh.containsValue("KEY3"));
	}

	public void testAddRemoveTS() {
		TimeSortedHashtable<String> tsh = new TimeSortedHashtable<String>();
		
		tsh.push("KEY1", 100);  // 100=KEY1
		tsh.push("KEY2", 100);  // 100=KEY1, 100=KEY2
		tsh.push("KEY3", 300);  // 100=KEY1, 100=KEY2, 300=KEY3
		tsh.push("KEY1", 200);  // 100=KEY2, 200=KEY1, 300=KEY3
		tsh.removeBefore(105);  // 200=KEY1, 300=KEY3
		

		assertEquals(2, tsh.size());
		assertEquals(2, tsh.countValuesAfter(0));
		assertEquals(2, tsh.countValuesAfter(100));
		assertEquals(1, tsh.countValuesAfter(201));
		assertEquals(0, tsh.countValuesAfter(301));
		assertTrue(tsh.containsValue("KEY1"));
		assertFalse(tsh.containsValue("KEY2"));
		assertTrue(tsh.containsValue("KEY3"));
	}
}
