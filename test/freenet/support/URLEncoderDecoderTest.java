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

/**
 * Test case for {@link freenet.support.URLEncoder} and 
 * {@link freenet.support.URLDecoder} classes.
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class URLEncoderDecoderTest extends TestCase {

	/**
	 * Tests if URLEncode.encode(String) and
	 * URLDecode.decode(String,boolean) methods
	 * work correctly together, both with safe
	 * characters and not safe.
	 */
	public void testEncodeDecodeString() {
		String[][] toEncode_encoded = {
				{"*-_./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz",""}, //safe chars
				{"!@#$%^&()+={}[]:;\"'<>,?~`\n",""}		//not safe chars
		};
		
		for (int i = 0; i < toEncode_encoded.length; i++)	//encoding
			toEncode_encoded[i][1] = URLEncoder.encode(toEncode_encoded[i][0]);
		
		try {
			for (int i = 0; i < toEncode_encoded.length; i++)	//decoding
				assertEquals(URLDecoder.decode(toEncode_encoded[i][1],false),toEncode_encoded[i][0]);
		} catch (URLEncodedFormatException anException) {
			fail("Not expected exception thrown : " + anException.getMessage()); }	
		
	}

}
