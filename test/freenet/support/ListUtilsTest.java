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
}
