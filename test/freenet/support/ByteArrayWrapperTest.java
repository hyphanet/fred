/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.ByteArrayWrapper} class.
 * @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class ByteArrayWrapperTest extends TestCase {

	private static final String DATA_STRING_1 = "asldkjaskjdsakdhasdhaskjdhaskjhbkasbhdjkasbduiwbxgdoudgboewuydxbybuewyxbuewyuwe";
	
	private static final String DATA_STRING_2 = "string2";
	
	public void testWrapper() {
		
		byte[] data1 = DATA_STRING_1.getBytes();
		byte[] data2 = DATA_STRING_2.getBytes();
		
		ByteArrayWrapper wrapper1 = new ByteArrayWrapper(data1);
		ByteArrayWrapper wrapper2 = new ByteArrayWrapper(data1);
		ByteArrayWrapper wrapper3 = new ByteArrayWrapper(data2);
		
		assertEquals(wrapper1, wrapper2);
		assertTrue(wrapper1.equals(wrapper2));
		assertFalse(wrapper2.equals(wrapper3));
		assertFalse(wrapper1.equals(""));

		Map<ByteArrayWrapper, ByteArrayWrapper> map = new HashMap<ByteArrayWrapper, ByteArrayWrapper>();
		
		map.put(wrapper1, wrapper1);
		map.put(wrapper2, wrapper2); // should clobber 1 by hashcode
		map.put(wrapper3, wrapper3);
		
		Object o1 = map.get(wrapper1);
		Object o2 = map.get(wrapper2);
		Object o3 = map.get(wrapper3);
		
		assertEquals(o1, o2);        // are wrapper1 and wrapper2 considered equivalent by hashcode?
		assertFalse(o1 == wrapper1); // did wrapper1 survive?
		assertTrue(o1 == wrapper2);  // did wrapper1 get replaced by 2?
		assertTrue(o3 == wrapper3);  // did wrapper3 get returned by hashcode correctly?		
	}
}
