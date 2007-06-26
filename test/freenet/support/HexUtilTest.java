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

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;

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
	 * The starting offset is always 0.
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
	 * Test the bitsToByte(BitSet,int) method
	 * against the bit representation of
	 * every possible single byte.
	 */
	public void testBitsToBytes_BitSetInt() {
		byte[] expectedByteArray = new byte[1];
		byte[] outputArray = new byte[1];
		BitSet methodBitSet = new BitSet(8);
		for (int i = 0; i < 256; i++) {
			outputArray = HexUtil.bitsToBytes(methodBitSet, 8);
			expectedByteArray[0] = (byte)i;
			assertTrue(Arrays.equals(expectedByteArray,outputArray));
			addOne(methodBitSet);
		}
	}
	
	/**
	 * It adds 1 to a given BitSet 
	 * @param aBitSet
	 */
	private void addOne(BitSet aBitSet) {
		int bitSetIndex = 0;
		while (aBitSet.get(bitSetIndex)==true) {
			aBitSet.flip(bitSetIndex);
			bitSetIndex++;
		}
		aBitSet.flip(bitSetIndex);
	}
	
	/**
	 * Test countBytesForBits(int) method
	 * against all possible values until 256 bytes
	 */
	public void testCountBytesForBits_int() {
		assertEquals(HexUtil.countBytesForBits(0),0);	//border case
		for (int expectedBytesCount = 1; expectedBytesCount < 256; expectedBytesCount++)
			for (int bits = (expectedBytesCount-1)*8+1; bits <= (expectedBytesCount)*8; bits++)
				assertEquals(HexUtil.countBytesForBits(bits),expectedBytesCount);
	}
	
	/**
	 * Test bytesToBits(byte[],BitSet,int) method
	 * against all possible single byte value.
	 * It uses HexUtil.bitsToBytes() method for the check,
	 * so be sure that method works correctly!
	 */
	public void testBytesToBits_byteBitSetInt() {
		byte[] methodByteArray = new byte[1];
		BitSet methodBitSet = new BitSet(8);
		for (int i = 0; i < 255; i++) {
			methodByteArray[0] = (byte)i;
			HexUtil.bytesToBits(methodByteArray,methodBitSet,7);
			assertTrue(Arrays.equals(methodByteArray,HexUtil.bitsToBytes(methodBitSet,8)));}
	}
	
	/**
	 * Test biToHex(BigInteger) method
	 * comparing its results to results provided
	 * by different scientific valid calculators.
	 */
	public void testBiToHex_BigInteger() {
		BigInteger methodBigInteger = new BigInteger("999999999999999");
		String expectedHexValue = "038d7ea4c67fff";
		assertEquals(HexUtil.biToHex(methodBigInteger),expectedHexValue);
		methodBigInteger = new BigInteger("0");
		expectedHexValue = "00";
		assertEquals(HexUtil.biToHex(methodBigInteger),expectedHexValue);
		methodBigInteger = new BigInteger("72057594037927935");
		expectedHexValue = "00ffffffffffffff";
		assertEquals(HexUtil.biToHex(methodBigInteger),expectedHexValue);
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
	
	/**
	 * Test hexToBytes(String,byte[],int) method
	 * with a too long offset.
	 * The method should raise an exception.
	 */
	public void testHexToBytes_StringByteInt_WithLongOffset() {
        try {
        	String methodString = "0";
        	byte[] methodByteArray = new byte[1];
    		HexUtil.hexToBytes(methodString,methodByteArray,methodByteArray.length);
            fail("Expected Exception Error Not Thrown!"); } 
        catch (ArrayIndexOutOfBoundsException anException) {
            assertNotNull(anException); }
    }
	
	/**
	 * Test hexToBytes(String,byte[],int) method
	 * with a too short byte[] to put the result.
	 * The method should raise an exception.
	 */
	public void testHexToBytes_StringByteInt_WithShortArray() {
        try {
        	String methodString = "0000";
        	byte[] methodByteArray = new byte[1];
    		HexUtil.hexToBytes(methodString,methodByteArray,0);
            fail("Expected Exception Error Not Thrown!"); } 
        catch (IndexOutOfBoundsException anException) {
            assertNotNull(anException); }
    }
	
	/**
	 * Test all hexToBytes() methods
	 * with a not valid character.
	 * The method should raise an exception. 
	 */
	public void testHexToBytes_WithBadDigit() {
		String methodString = "00%0";
		try {
        	byte[] methodByteArray = new byte[methodString.length()];
    		HexUtil.hexToBytes(methodString,methodByteArray,0);
            fail("Expected Exception Error Not Thrown!"); } 
        catch (NumberFormatException anException) {
            assertNotNull(anException); }
        try {
    		HexUtil.hexToBytes(methodString,0);
            fail("Expected Exception Error Not Thrown!"); } 
        catch (NumberFormatException anException) {
            assertNotNull(anException); }
        try {
    		HexUtil.hexToBytes(methodString);
            fail("Expected Exception Error Not Thrown!"); } 
        catch (NumberFormatException anException) {
            assertNotNull(anException); }
    }
	
	/**
	 * Test the bitsToByte(BitSet,int) method
	 * using a size smaller than the actual number
	 * of bits in the BitSet.
	 */
	public void testBitsToBytes_WithShortSize() {
		byte[] expectedByteArray = new byte[1];
		byte[] outputArray = new byte[1];
		BitSet methodBitSet = new BitSet(8);
		methodBitSet.flip(3);
		outputArray = HexUtil.bitsToBytes(methodBitSet,3);	//TODO: verify this strange method
		expectedByteArray[0] = (byte)8;
		assertTrue(Arrays.equals(expectedByteArray,outputArray));
	}
}
