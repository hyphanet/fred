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

package freenet.support;

import java.lang.reflect.InvocationTargetException;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.Loader} class.
 * 
 * @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class LoaderTest extends TestCase {

	public void testLoader() {
		Object o = null;
		
		try {
			o = Loader.getInstance("java.lang.String");
		} catch (InvocationTargetException e) {
			fail("unexpected exception" + e.getMessage());
		} catch (NoSuchMethodException e) {
			fail("unexpected exception" + e.getMessage());
		} catch (InstantiationException e) {
			fail("unexpected exception" + e.getMessage());
		} catch (IllegalAccessException e) {
			fail("unexpected exception" + e.getMessage());
		} catch (ClassNotFoundException e) {
			fail("unexpected exception" + e.getMessage());
		}
		
		assertTrue(o instanceof java.lang.String);
	}
}
