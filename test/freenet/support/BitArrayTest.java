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

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.BitArray} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class BitArrayTest extends TestCase {

	private final int sampleBitsNumber = 10;
	private final int oneByteBits = 8;
	
	/**
	 * Creates a BitArray with all values set to the
	 * boolean argument
	 * @param arraySize the size of the BitArray
	 * @param value the value for each bit
	 * @return the set BitArray
	 */
	private BitArray createAllEqualsBitArray(int arraySize, boolean value) {
		BitArray methodBitArray = new BitArray(arraySize);
		//setting all bits true
		for (int i=0; i<methodBitArray.getSize();i++)
			methodBitArray.setBit(i,value);
		return methodBitArray;
	}
	
	/**
	 * Creates a String of toRepeat String as long as needed
	 * @param stringSize length requested
	 * @param toRepeat String to repeat stringSize times
	 * @return the String of toRepeat
	 */
	private String createAllOneString(int stringSize, String toRepeat) {
		StringBuilder methodStringBuilder = new StringBuilder();
		for (int i=0;i<stringSize;i++)
			methodStringBuilder.append(toRepeat);
		return methodStringBuilder.toString();
	}

	/**
	 * Tests BitArray(int) constructor
	 * and verifies if the instance is
	 * well created (all values must be
	 * readables and false, and the length
	 * has to be correct)
	 */
	public void testBitArray_int() {
		BitArray methodBitArray = new BitArray(sampleBitsNumber);
		for(int i=0;i<sampleBitsNumber;i++)
			assertFalse(methodBitArray.bitAt(i));
		assertEquals(methodBitArray.getSize(),sampleBitsNumber);
	}
	
	/**
	 * Tests toString() method
	 * creating BitArrays with same value bits.
	 */
	public void testToStringAllEquals() {
		BitArray methodBitArray = createAllEqualsBitArray(sampleBitsNumber,true);
		String expectedString = createAllOneString(sampleBitsNumber,"1");
		assertEquals(methodBitArray.toString(),expectedString);
		methodBitArray = createAllEqualsBitArray(sampleBitsNumber,false);
		expectedString = createAllOneString(sampleBitsNumber,"0");
		assertEquals(methodBitArray.toString(),expectedString);
	}
	
	/**
	 * Tests toString() method
	 * with a BitArray with size zero.
	 */
	public void testToStringEmpty() {
		BitArray methodBitArray = new BitArray(0);
		assertEquals(methodBitArray.toString().length(),0);
	}
	
	/**
	 * Tests setBit(int,boolean) method
	 * trying to set a bit out of bounds
	 */
	public void testSetBit_OutOfBounds() {
		BitArray methodBitArray = new BitArray(sampleBitsNumber);
		try {
			methodBitArray.setBit(sampleBitsNumber,true); 
			//fail("Expected Exception Error Not Thrown!");
			} 
		catch (ArrayIndexOutOfBoundsException anException) { 
			assertNotNull(anException); }
	}

	/**
	 * Tests setBit(int,boolean) method
	 * using getAt(int) to verify if they are
	 * consistent.
	 */
	public void testSetAndGetBit() {
		BitArray methodBitArray = new BitArray(sampleBitsNumber);
		//setting true even bits
		for (int i=0; i<methodBitArray.getSize();i=i+2)
			methodBitArray.setBit(i,true);
		//checking even bits
		for (int i=0; i<methodBitArray.getSize();i=i+2)
			assertTrue(methodBitArray.bitAt(i));
		//checking odd bits
		for (int i=1; i<methodBitArray.getSize();i=i+2)
			assertFalse(methodBitArray.bitAt(i));
	}

	/**
	 * Tests unsignedByteToInt(byte) method
	 * trying it correctness for every possible (i.e. 256) 
	 * byte value
	 */
	public void testUnsignedByteToInt() {
		byte sampleByte;
		for (int i =0; i<256; i++) {
			sampleByte = (byte)i;
			assertEquals(i,BitArray.unsignedByteToInt(sampleByte)); }
	}

	/**
	 * Tests getSize() method
	 */
	public void testGetSize() {
		BitArray methodBitArray = new BitArray(0);
		assertEquals(methodBitArray.getSize(),0);
		methodBitArray = createAllEqualsBitArray(sampleBitsNumber,true);
		assertEquals(methodBitArray.getSize(),sampleBitsNumber);
	}

	/**
	 * Tests setAllOnes() method
	 * comparing the result to
	 * a BitArray with already all ones
	 * set.
	 */
	public void testSetAllOnes() {
		BitArray methodBitArray = createAllEqualsBitArray(sampleBitsNumber,true);
		BitArray methodBitArrayToVerify = new BitArray(sampleBitsNumber);
		methodBitArrayToVerify.setAllOnes();
		assertEquals(methodBitArray,methodBitArrayToVerify);
	}

	/**
	 * Tests firstOne() method
	 * far all possible first-one-position
	 * in a BitArray with as many bits as in
	 * a single byte
	 */
	public void testFirstOne() {
		BitArray methodBitArray = new BitArray(oneByteBits);
		//only one "1"
		for(int i=0; i<oneByteBits; i++) {
			methodBitArray = new BitArray(oneByteBits);
			methodBitArray.setBit(i,true);
			assertEquals(methodBitArray.firstOne(),i);}
		
		methodBitArray.setAllOnes();
		//augmenting zeros
		for(int i=0; i<oneByteBits-1; i++) {
			methodBitArray.setBit(i,false);
			assertEquals(methodBitArray.firstOne(),i+1);}
		//all zeros
		methodBitArray.setBit(oneByteBits-1,false);
		assertEquals(methodBitArray.firstOne(),-1);
	}
	
	public void testLastOne() {
		BitArray array = new BitArray(16);
		array.setAllOnes();
		for(int i=15;i>=0;i--) {
			assertEquals(i, array.lastOne(Integer.MAX_VALUE));
			assertEquals(i, array.lastOne(i+1));
			assertEquals(i, array.lastOne(i+8));
			array.setBit(i, false);
		}
		assert(array.lastOne(Integer.MAX_VALUE) == -1);
		assert(array.lastOne(0) == -1);
	}
	
	public void testShrinkGrow() {
		BitArray array = new BitArray(16);
		array.setAllOnes();
		array.setSize(9);
		array.setSize(16);
		for(int i=9;i<16;i++)
			assert(!array.bitAt(i));
		for(int i=0;i<9;i++)
			assert(array.bitAt(i));
	}

}
