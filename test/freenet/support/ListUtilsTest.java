/* This program is free software; you can redistribute it and/or modify
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
package freenet.support;

import java.util.ArrayList;
import java.util.Random;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.ListUtils} class.
 *
 */
public class ListUtilsTest extends TestCase {

	public void testRemoveByObject() {
		ArrayList<Integer> list = new ArrayList<Integer>();
		for(int i = 0; i < 10; i++)
			list.add(Integer.valueOf(i));
		// 0 1 2 3 4 5 6 7 8 9
		assertEquals(list.size(), 10);
		{
			int oldSize = list.size();
			// remove non-existing element
			assertFalse(ListUtils.removeBySwapLast(list, Integer.valueOf(oldSize+1)));
			assertEquals(list.size(), oldSize);
		}
		// 0 1 2 3 4 5 6 7 8 9
		for(int i = 0; i < list.size(); i++)
			assertEquals(list.get(i), Integer.valueOf(i));
		{
			// remove last element
			int oldSize = list.size();
			Integer oldTop = list.get(oldSize-1);
			assertTrue(ListUtils.removeBySwapLast(list, oldTop));
			// 0 1 2 3 4 5 6 7 8
			assertFalse(list.contains(oldTop));
			assertEquals(list.size(), oldSize-1);
		}
		for(int i = 0; i < list.size(); i++)
			assertEquals(list.get(i), Integer.valueOf(i));
		{
			// remove first element
			int oldSize = list.size();
			Integer oldFirst = list.get(0);
			Integer oldTop = list.get(oldSize-1);
			assertTrue(ListUtils.removeBySwapLast(list, oldFirst));
			// 8 1 2 3 4 5 6 7
			assertFalse(list.contains(oldFirst));
			assertEquals(list.size(), oldSize-1);
			assertEquals(list.get(0), oldTop);
			for(int i = 1; i < list.size(); i++)
				assertEquals(list.get(i), Integer.valueOf(i));
		}
	}
	public void testRemoveByIndex() {
		ArrayList<Integer> list = new ArrayList<Integer>();
		for(int i = 0; i < 10; i++)
			list.add(Integer.valueOf(i));
		// 0 1 2 3 4 5 6 7 8 9
		assertEquals(list.size(), 10);
		{
			// remove last element
			int oldSize = list.size();
			Integer oldTop = list.get(oldSize-1);
			assertEquals(ListUtils.removeBySwapLast(list, oldSize-1), oldTop);
			// 0 1 2 3 4 5 6 7 8
			assertEquals(list.size(), oldSize-1);
			assertFalse(list.contains(oldTop));
		}
		for(int i = 0; i < list.size(); i++)
			assertEquals(list.get(i), Integer.valueOf(i));
		{
			int oldSize = list.size();
			Integer oldFirst = list.get(0);
			Integer oldTop = list.get(oldSize-1);
			// remove first element
			assertEquals(ListUtils.removeBySwapLast(list, 0), oldTop);
			// 8 1 2 3 4 5 6 7
			assertFalse(list.contains(oldFirst));
			assertEquals(list.size(), oldSize-1);
			assertEquals(list.get(0), oldTop);
		}
		for(int i = 1; i < list.size(); i++)
			assertEquals(list.get(i), Integer.valueOf(i));
	}

	static class NotRandomAlwaysTop extends Random {
		// Fake random, always remove highest possible value in nextInt
		@Override
			public int nextInt(int top) {
				return top - 1;
			}
	}
	static class NotRandomAlwaysZero extends Random {
		// Fake random, always remove lowest possible value in nextInt
		@Override
			public int nextInt(int top) {
				return 0;
			}
	}
	public void testRemoveByRandom() {
		ArrayList<Integer> list = new ArrayList<Integer>();
		Random rand = new Random();
		for(int i = 0; i < 10; i++)
			list.add(Integer.valueOf(i));
		ListUtils.RandomRemoveResult<Integer> res;
		for(int i = 0; i < 10; i++) {
			assertEquals(list.size(), 10-i);
			Integer oldTop = list.get(list.size()-1);
			assertNotNull(oldTop);
			res = ListUtils.removeRandomBySwapLast(rand, list);
			assertNotNull(res);
			assertEquals(list.size(), 10-i-1);
			assertFalse(list.contains(res.removed));
			assertTrue(res.removed.equals(res.moved) || list.contains(res.moved));
		}
		assertNull(ListUtils.removeRandomBySwapLast(rand, list));
		assertEquals(list.size(), 0);

		for(int i = 0; i < 10; i++)
			list.add(Integer.valueOf(i));
		assertEquals(list.size(), 10);

		rand = new NotRandomAlwaysTop();
		assertEquals(rand.nextInt(1000), 999);
		assertEquals(rand.nextInt(100), 99);
		assertEquals(rand.nextInt(10), 9);

		// fake random will always remove last element
		// (actually, this test relies on current implementation,
		// not enforced by specification)
		{
			int oldSize = list.size();
			Integer oldTop = list.get(oldSize-1);
			res = ListUtils.removeRandomBySwapLast(rand, list);
			assertEquals(list.size(), oldSize-1);
			assertNotNull(res);
			assertEquals(res.moved, oldTop);
			assertEquals(res.moved, res.removed);
		}
		for(int i = 0; i < list.size(); i++)
			assertEquals(list.get(i), Integer.valueOf(i));

		rand = new NotRandomAlwaysZero();
		assertEquals(rand.nextInt(1000), 0);
		assertEquals(rand.nextInt(100), 0);
		assertEquals(rand.nextInt(10), 0);

		// fake random will always remove first element
		// (actually, this test relies on current implementation,
		// not enforced by specification)
		{
			int oldSize = list.size();
			Integer oldFirst = list.get(0);
			Integer oldTop = list.get(oldSize-1);
			res = ListUtils.removeRandomBySwapLast(rand, list);
			assertEquals(list.size(), oldSize-1);
			assertNotNull(res);
			assertEquals(res.removed, oldFirst);
			assertEquals(res.moved, oldTop);
			assertEquals(list.get(0), oldTop);
		}
		for(int i = 1; i < list.size(); i++)
			assertEquals(list.get(i), Integer.valueOf(i));
	}

	public void testRemoveByRandomSimple() {
		ArrayList<Integer> list = new ArrayList<Integer>();
		Random rand = new Random();
		for(int i = 0; i < 10; i++)
			list.add(Integer.valueOf(i));
		Integer res;
		for(int i = 0; i < 10; i++) {
			assertEquals(list.size(), 10-i);
			Integer oldTop = list.get(list.size()-1);
			assertNotNull(oldTop);
			res = ListUtils.removeRandomBySwapLastSimple(rand, list);
			assertNotNull(res);
			assertEquals(list.size(), 10-i-1);
			assertFalse(list.contains(res));
		}
		assertNull(ListUtils.removeRandomBySwapLastSimple(rand, list));
		assertEquals(list.size(), 0);

		for(int i = 0; i < 10; i++)
			list.add(Integer.valueOf(i));

		rand = new NotRandomAlwaysTop();
		assertEquals(rand.nextInt(1000), 999);
		assertEquals(rand.nextInt(100), 99);
		assertEquals(rand.nextInt(10), 9);

		assertEquals(list.size(), 10);

		// fake random will always remove last element
		// (actually, this test relies on current implementation,
		// not enforced by specification)
		{
			int oldSize = list.size();
			Integer oldTop = list.get(oldSize-1);
			res = ListUtils.removeRandomBySwapLastSimple(rand, list);
			assertEquals(list.size(), oldSize-1);
			assertNotNull(res);
			assertEquals(res, oldTop);
		}
		for(int i = 0; i < list.size(); i++)
			assertEquals(list.get(i), Integer.valueOf(i));

		rand = new NotRandomAlwaysZero();
		assertEquals(rand.nextInt(1000), 0);
		assertEquals(rand.nextInt(100), 0);
		assertEquals(rand.nextInt(10), 0);

		// fake random will always remove first element
		// (actually, this test relies on current implementation,
		// not enforced by specification)
		{
			int oldSize = list.size();
			Integer oldFirst = list.get(0);
			Integer oldTop = list.get(oldSize-1);
			res = ListUtils.removeRandomBySwapLastSimple(rand, list);
			assertEquals(list.size(), oldSize-1);
			assertNotNull(res);
			assertEquals(res, oldFirst);
			assertEquals(list.get(0), oldTop);
		}
		for(int i = 1; i < list.size(); i++)
			assertEquals(list.get(i), Integer.valueOf(i));
	}
}
