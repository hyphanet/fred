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
	
	public void testStringToBool() {
		assertTrue(Fields.stringToBool("true"));
		assertTrue(Fields.stringToBool("TRUE"));
		assertFalse(Fields.stringToBool("false"));
		assertFalse(Fields.stringToBool("FALSE"));
		
		try {
			Fields.stringToBool("Free Tibet");
			fail();
		}
		catch(NumberFormatException e) {
			// expect this
		}
		
		try {
			Fields.stringToBool(null);
			fail();
		}
		catch(NumberFormatException e) {
			// expect this
		}
	}
	
	public void testStringToBoolWithDefault() {
		assertTrue(Fields.stringToBool("true", false));
		assertFalse(Fields.stringToBool("false", true));
		assertTrue(Fields.stringToBool("TruE", false));
		assertFalse(Fields.stringToBool("faLSE", true));
		assertTrue(Fields.stringToBool("trueXXX", true));
		assertFalse(Fields.stringToBool("XXXFalse", false));
		assertTrue(Fields.stringToBool(null, true));
	}
	
	public void testBoolToString() {
		assertEquals(Fields.boolToString(true), "true");
		assertEquals(Fields.boolToString(false), "false");
	}
	
	public void testCommaListFromString() {
		String[] expected = new String[] {"one", "two", "three", "four"};
		String[] actual = Fields.commaList("one,two,     three    ,  four");
		
		for(int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actual[i]);
		}
		
		// null
		assertNull(Fields.commaList((String)null));
		
		// no items
		expected = new String[] {};
		actual = Fields.commaList("");
		
		assertTrue(expected.length == actual.length);
	}
	
	public void testStringArrayToCommaList() {
		
		String[] input = new String[] { "one", "two", "three", "four" };
		
		String expected = "one,two,three,four";
		String actual = Fields.commaList(input);
		
		assertEquals(expected, actual);
		
		// empty
		input = new String[] {};
		
		expected = "";
		actual = Fields.commaList(input);
		
		assertEquals(expected, actual);
	}
	
	public void testHashcodeForByteArray() {
		byte[] input = new byte[] { 0, 1, 2, 3, 4, 5, 6, 7 };
		
		assertEquals(67372036, Fields.hashCode(input));
		
		// empty
		input = new byte[] {};
		
		assertEquals(0, Fields.hashCode(input));
	}
}
