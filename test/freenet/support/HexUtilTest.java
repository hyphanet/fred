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

import java.util.Arrays;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.HexUtil} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class HexUtilTest extends TestCase {

	/**
	 * Test the bytesToHex(byte[]) method
	 * against every possible single byte value.
	 */
	public void testBytesToHex_byte() {
		byte[] methodByteArray = new byte[1];
		String expectedResult;
		for (int i = 255; i >= 0; i--) {
			methodByteArray[0] = (byte)i;
			/* Integer.toHexString works with int so it doesn't return always a two digit hex. 
			   For this reason we need the next "switch case". */
			expectedResult = (i <= 15?
					"0" + (Integer.toHexString(i)):
					(Integer.toHexString(i)));
			assertEquals(expectedResult,HexUtil.bytesToHex(methodByteArray));}
	}
	
	/**
	 * Test the hexToBytes(String) method
	 * against the hex representation of
	 * every possible single byte.
	 */
	public void testHexToBytes_String() {
		byte[] expectedByteArray = new byte[1];
		String methodHexString;
		for (int i = 255; i >= 0; i--) {
			expectedByteArray[0] = (byte)i;
			/* Integer.toHexString works with int so it doesn't return always a two digit hex. 
			   For this reason we need the next "switch case". */
			methodHexString = (i <= 15?
					"0" + (Integer.toHexString(i)):
					(Integer.toHexString(i)));
			assertTrue(Arrays.equals(expectedByteArray,HexUtil.hexToBytes(methodHexString)));}
	}
	
	/**
	 * Test the hexToBytes(String,int) method
	 * against the hex representation of
	 * every possible single byte.
	 * The starting offset is always 0.
	 */
	public void testHexToBytes_StringInt() {
		byte[] expectedByteArray = new byte[1];
		String methodHexString;
		for (int i = 255; i >= 0; i--) {
			expectedByteArray[0] = (byte)i;
			/* Integer.toHexString works with int so it doesn't return always a two digit hex. 
			   For this reason we need the next "switch case". */
			methodHexString = (i <= 15?
					"0" + (Integer.toHexString(i)):
					(Integer.toHexString(i)));
			assertTrue(Arrays.equals(expectedByteArray,HexUtil.hexToBytes(methodHexString,0)));}
	}
	
	/**
	 * Test the hexToBytes(String,byte[],int) method
	 * against the hex representation of
	 * every possible single byte.
	 */
	public void testHexToBytes_StringByteInt() {
		byte[] expectedByteArray = new byte[1];
		byte[] outputArray = new byte[1];
		String methodHexString;
		for (int i = 255; i >= 0; i--) {
			expectedByteArray[0] = (byte)i;
			/* Integer.toHexString works with int so it doesn't return always a two digit hex. 
			   For this reason we need the next "switch case". */
			methodHexString = (i <= 15?
					"0" + (Integer.toHexString(i)):
					(Integer.toHexString(i)));
			HexUtil.hexToBytes(methodHexString,outputArray,0);
			assertTrue(Arrays.equals(expectedByteArray,outputArray));}
	}
	
	/**
	 * Test bytesToHex(byte[],int,int) method
	 * with a too long starting offset. The tested
	 * method should raise an exception.
	 */
	public void testBytesToHex_byteIntInt_WithLongOffset() {
        try {
        	int arrayLength = 3;
        	byte[] methodBytesArray = new byte[arrayLength];
    		HexUtil.bytesToHex(methodBytesArray,arrayLength+1,1);
            fail("Expected Exception Error Not Thrown!"); } 
        catch (IllegalArgumentException anException) {
            assertNotNull(anException); }
    }
	
	/**
	 * Test bytesToHex(byte[],int,int) method
	 * with asking to read too many bytes. The tested
	 * method should raise an exception.
	 */
	public void testBytesToHex_byteIntInt_WithLongReading() {
        try {
        	int arrayLength = 3;
        	byte[] methodBytesArray = new byte[arrayLength];
    		HexUtil.bytesToHex(methodBytesArray,0,arrayLength+1);
            fail("Expected Exception Error Not Thrown!"); } 
        catch (IllegalArgumentException anException) {
            assertNotNull(anException); }
    }
	
	/**
	 * Test bytesToHex(byte[],int,int) method
	 * with a 0 length.
	 */
	public void testBytesToHex_byteIntInt_WithZeroLength() {
		int length = 0;
		byte[] methodBytesArray = {1,2,3};		//a non-zero bytes array
		assertEquals("",HexUtil.bytesToHex(methodBytesArray,0,length));
	}
}
