/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.Fields} class.
 * 
 *  @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class FieldsTest extends TestCase {

	public void testHexToLong(){
		
		long l1 = Fields.hexToLong("0");
		assertEquals(l1, 0);
		
		l1 = Fields.hexToLong("000000");
		assertEquals(l1, 0);
		
		l1 = Fields.hexToLong("1");
		assertEquals(l1, 1);
		
		l1 = Fields.hexToLong("a");
		assertEquals(l1, 10);
		
		l1 = Fields.hexToLong("ff");
		assertEquals(l1, 255);
		
		l1 = Fields.hexToLong("ffffffff");
		assertEquals(l1, 4294967295L);
		
		l1 = Fields.hexToLong("7fffffffffffffff");
		assertEquals(l1, Long.MAX_VALUE); 
		
		l1 = Fields.hexToLong("8000000000000000");
		assertEquals(l1, Long.MIN_VALUE); 
		
		l1 = Fields.hexToLong("FFfffFfF"); // mix case
		assertEquals(l1, 4294967295L);

		try {
			l1 = Fields.hexToLong("abcdef123456789aa"); // 17 chars
			fail();
		}
		catch(NumberFormatException e){
			// expect this
		}
		
		try {
			l1 = Fields.hexToLong("DeADC0dER"); // invalid char
			fail();
		}
		catch(NumberFormatException e){
			// expect this
		}
		
		// see javadoc
		l1 = Fields.hexToLong(Long.toHexString(20));
		assertEquals(20, l1);

		l1 = Fields.hexToLong(Long.toHexString(Long.MIN_VALUE));
		assertEquals(Long.MIN_VALUE, l1);

		// see javadoc
		try {
			String longAsString = Long.toString(-1, 16);
			l1 = Fields.hexToLong(longAsString);
			fail();
		}
		catch(NumberFormatException e) {
			// expect this
		}
	}
	
	public void testHexToInt() {
		
		int i1 = Fields.hexToInt("0");
		assertEquals(i1, 0);
		
		i1 = Fields.hexToInt("000000");
		assertEquals(i1, 0);
		
		i1 = Fields.hexToInt("1");
		assertEquals(i1, 1);
		
		i1 = Fields.hexToInt("a");
		assertEquals(i1, 10);
		
		i1 = Fields.hexToInt("ff");
		assertEquals(i1, 255);
		
		i1 = Fields.hexToInt("80000000");
		assertEquals(i1, Integer.MIN_VALUE);
		
		i1 = Fields.hexToInt("0000000080000000"); // 16 chars
		assertEquals(i1, Integer.MIN_VALUE);
		
		i1 = Fields.hexToInt("7fffffff");
		assertEquals(i1, Integer.MAX_VALUE);
		
		try {
			i1 = Fields.hexToInt("0123456789abcdef0"); // 17 chars
			fail();
		}
		catch(NumberFormatException e){
			// expect this
		}
		
		try {
			i1 = Fields.hexToInt("C0dER"); // invalid char
			fail();
		}
		catch(NumberFormatException e){
			// expect this
		}
		
		// see javadoc
		i1 = Fields.hexToInt(Integer.toHexString(20));
		assertEquals(20, i1);

		i1 = Fields.hexToInt(Long.toHexString(Integer.MIN_VALUE));
		assertEquals(Integer.MIN_VALUE, i1);

		// see javadoc
		try {
			String integerAsString = Integer.toString(-1, 16);
			i1 = Fields.hexToInt(integerAsString);
			fail();
		}
		catch(NumberFormatException e) {
			// expect this
		}
	}
}
