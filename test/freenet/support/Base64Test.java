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

import junit.framework.TestCase;
import java.util.Arrays;

/**
 * Test case for {@link freenet.support.Base64} class.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class Base64Test extends TestCase {
	
	/**
	 * Test the encode(byte[]) method
	 * against a well-known example
	 * (see http://en.wikipedia.org/wiki/Base_64 as reference)
	 * to verify if it encode works correctly.
	 */
	public void testEncode() {
		String toEncode = "Man is distinguished, not only by his reason, but by this singular passion from other animals, which is a lust of the mind, that by a perseverance of delight in the continued and indefatigable generation of knowledge, exceeds the short vehemence of any carnal pleasure.";
		String expectedResult = "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24sIGJ1dCBieSB0aGlzIHNpbmd1bGFyIHBhc3Npb24gZnJvbSBvdGhlciBhbmltYWxzLCB3aGljaCBpcyBhIGx1c3Qgb2YgdGhlIG1pbmQsIHRoYXQgYnkgYSBwZXJzZXZlcmFuY2Ugb2YgZGVsaWdodCBpbiB0aGUgY29udGludWVkIGFuZCBpbmRlZmF0aWdhYmxlIGdlbmVyYXRpb24gb2Yga25vd2xlZGdlLCBleGNlZWRzIHRoZSBzaG9ydCB2ZWhlbWVuY2Ugb2YgYW55IGNhcm5hbCBwbGVhc3VyZS4";
		byte[] aByteArrayToEncode = toEncode.getBytes();
		assertEquals(Base64.encode(aByteArrayToEncode),expectedResult);
	}
	
	/**
	 * Test the decode(String) method
	 * against a well-known example
	 * (see http://en.wikipedia.org/wiki/Base_64 as reference)
	 * to verify if it decode an already encoded string correctly.
	 */	
	public void testDecode() {
		String toDecode = "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24sIGJ1dCBieSB0aGlzIHNpbmd1bGFyIHBhc3Npb24gZnJvbSBvdGhlciBhbmltYWxzLCB3aGljaCBpcyBhIGx1c3Qgb2YgdGhlIG1pbmQsIHRoYXQgYnkgYSBwZXJzZXZlcmFuY2Ugb2YgZGVsaWdodCBpbiB0aGUgY29udGludWVkIGFuZCBpbmRlZmF0aWdhYmxlIGdlbmVyYXRpb24gb2Yga25vd2xlZGdlLCBleGNlZWRzIHRoZSBzaG9ydCB2ZWhlbWVuY2Ugb2YgYW55IGNhcm5hbCBwbGVhc3VyZS4=";
		String expectedResult = "Man is distinguished, not only by his reason, but by this singular passion from other animals, which is a lust of the mind, that by a perseverance of delight in the continued and indefatigable generation of knowledge, exceeds the short vehemence of any carnal pleasure.";
		try {
			String decodedString = new String(Base64.decode(toDecode));
			assertEquals(decodedString,expectedResult);
		} catch (IllegalBase64Exception aException) {
			fail("Not expected exception thrown : " + aException.getMessage()); }
	}
	
	/**
	 * Test encode(byte[] in)
	 * and decode(String inStr) methods,
	 * to verify if they work correctly together.
	 * It compares the string before encoding
	 * and with the one after decoding.
	 */
	public void testEncodeDecode() {
		byte[] bytesDecoded;
		byte[] bytesToEncode = new byte[5];
		
		bytesToEncode[0] = 127;		//byte upper bound
		bytesToEncode[1] = 64;
		bytesToEncode[2] = 0;
		bytesToEncode[3] = -64;
		bytesToEncode[4] = -128;	//byte lower bound
		
		String aBase64EncodedString = Base64.encode(bytesToEncode);
		
		try {
			bytesDecoded = Base64.decode(aBase64EncodedString);
			assertTrue(Arrays.equals(bytesToEncode,bytesDecoded)); } 
		catch (IllegalBase64Exception aException) {
			fail("Not expected exception thrown : " + aException.getMessage()); }
	}
	
	/**
	 * Test the encode(String,boolean)
	 * method to verify if the padding
	 * character '=' is correctly placed.
	 */
	public void testEncodePadding() {
		byte[][] methodBytesArray = {
				{4,4,4},	//three byte Array -> no padding char expected	
				{4,4},		//two byte Array -> one padding char expected
				{4}};		//one byte Array -> two padding-chars expected	
		String encoded;
		
		for (int i = 0; i<methodBytesArray.length; i++) {
			encoded = Base64.encode(methodBytesArray[i],true);
			if (i == 0)
				assertEquals(encoded.indexOf('='),-1);	//no occurrences expected
			else
				assertEquals(encoded.indexOf('='),encoded.length()-i);
		}
	}
	
	/**
	 * Test if the decode(String) method
	 * raise correctly an exception when
	 * providing a string with non-Base64
	 * characters.
	 */
	public void testIllegalBaseCharacter() {
		String illegalCharString = "abcd=fghilmn";		//TODO: check many other possibile cases!
		try {
			Base64.decode(illegalCharString);
			fail("Expected IllegalBase64Exception not thrown"); }
		catch (IllegalBase64Exception exception) {
			assertSame("illegal Base64 character",exception.getMessage()); }
	}
	
	/**
	 * Test if the decode(String) method
	 * raise correctly an exception when
	 * providing a string with a 
	 * wrong Base64 length.
	 * (as we can consider not-padded strings too,
	 *  the only wrong lengths are the ones
	 *  where -> number MOD 4 = 1).
	 */
	public void testIllegalBaseLength() {
		String illegalLengthString = "a";		//most interesting case
		try {
			Base64.decode(illegalLengthString);
			fail("Expected IllegalBase64Exception not thrown"); }
		catch (IllegalBase64Exception exception) {
			assertSame("illegal Base64 length",exception.getMessage()); }
	}
}
