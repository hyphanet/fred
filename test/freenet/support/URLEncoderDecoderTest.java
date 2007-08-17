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

import java.io.UnsupportedEncodingException;
import freenet.utils.*;
import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.URLEncoder} and 
 * {@link freenet.support.URLDecoder} classes.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class URLEncoderDecoderTest extends TestCase {

	private String prtblAscii = new String(UTFUtil.PRINTABLE_ASCII);
	private String stressedUTF_8Chars = new String(UTFUtil.STRESSED_UTF);
			
	/**
	 * Tests if URLEncode.encode(String) and
	 * URLDecode.decode(String,boolean) methods
	 * work correctly together, both with safe
	 * characters and not safe "base" (i.e. ASCII) chars .
	 */
	public void testEncodeDecodeString_notSafeBaseChars() {
		String[] toEncode = {
				//safe chars
				URLEncoder.safeURLCharacters,
				prtblAscii,
				//triple % char, if badly encoded it will generate an exception
				"%%%",
				//no chars
				""};
		try {
			assertTrue(areCorrectlyEncodedDecoded(toEncode));
		} catch (URLEncodedFormatException anException) {
			fail("Not expected exception thrown : " + anException.getMessage()); }
	}
	
	/**
	 * Tests if URLEncode.encode(String) and
	 * URLDecode.decode(String,boolean) methods
	 * work correctly together, both with safe
	 * characters and not safe "advanced" (i.e. not ASCII) chars .
	 */
	public void testEncodeDecodeString_notSafeAdvChars() {
		String[] toEncode = {stressedUTF_8Chars};
		try {
			assertTrue(areCorrectlyEncodedDecoded(toEncode));
		} catch (URLEncodedFormatException anException) {
			fail("Not expected exception thrown : " + anException.getMessage()); }
	}

	/**
	 * Verifies if a string is the same after
	 * being processed by encoding and 
	 * decoding methods
	 * @param toEncode String to Encode
	 * @return true means to be tolerant of bogus escapes
	 * @throws URLEncodedFormatException
	 */
	private boolean areCorrectlyEncodedDecoded(String[] toEncode) throws URLEncodedFormatException {
		boolean retValue = true;
		String[] encoded = new String[toEncode.length];
		//encoding
		for (int i = 0; i < encoded.length; i++)
			encoded[i] = URLEncoder.encode(toEncode[i]);
		//decoding
		for (int i = 0; i < encoded.length; i++)
			retValue &= (URLDecoder.decode(encoded[i],false)).equals(toEncode[i]);
		return retValue;
	}
	
	/**
	 * Tests encode(String,String,boolean) method
	 * to verify if the force parameter is
	 * well-managed for each safeURLCharacter,
	 * with both true and false ascii-flag.
	 */
	public void testEncodeForced() {
		String toEncode,expectedResult;
		char eachChar;
		for(int i=0; i<URLEncoder.safeURLCharacters.length(); i++) {
			eachChar = URLEncoder.safeURLCharacters.charAt(i);
			toEncode = String.valueOf(eachChar);
			try {
				expectedResult = "%"+ HexUtil.bytesToHex(
						//since safe chars are only US-ASCII
						toEncode.getBytes("US-ASCII")); 
				assertEquals(URLEncoder.encode(toEncode,toEncode,false),
						expectedResult);
				assertEquals(URLEncoder.encode(toEncode,toEncode,true),
						expectedResult);
			} catch (UnsupportedEncodingException anException) {
				fail("Not expected exception thrown : " + anException.getMessage()); }
		}
	}
	
	
	/**
	 * Verifies if a URLEncodedFormatException is raised
	 * when decoding the provided String
	 * @param toDecode the String to decode
	 * @param tolerant whether the decoding should be tolerant with 
	 * @return
	 */
	private boolean isDecodeRaisingEncodedException(String toDecode, boolean tolerant) {
		boolean retValue = false;
		try {
			System.out.println(URLDecoder.decode(toDecode,false));
		} catch (URLEncodedFormatException anException) {
			retValue = true; }
		return retValue;
	}
	
	/**
	 * Tests decode(String,boolean) method
	 * using not valid encoded String to
	 * verifies if it raises an exception
	 */
	public void testDecodeWrongString() {
		String toDecode = "%00";
		assertTrue(isDecodeRaisingEncodedException(toDecode,false));
	}
	
	/**
	 * Tests decode(String,boolean) method
	 * using not valid hex values String to
	 * verifies if it raises an exception
	 */
	public void testDecodeWrongHex() {
		String toDecode = "123456789abcde"+prtblAscii+stressedUTF_8Chars;
		
		for (int i = 0; i<toDecode.length(); i++)
			assertTrue(
					isDecodeRaisingEncodedException("%"+toDecode.substring(i,i+1),false));
	}
	
	/**
	 * Tests decode(String,boolean) method
	 * trying the boolean argument, to verify
	 * if it work correctly as a hack to allow users 
	 * to paste in URLs containing %'s.
	 */
	public void testTolerantDecoding() {
		String toDecode = "%%%";
		
		try {
			assertEquals(URLDecoder.decode(toDecode,true),toDecode);
		} catch (URLEncodedFormatException anException) {
			fail("Not expected exception thrown : " + anException.getMessage()); }
	}
}