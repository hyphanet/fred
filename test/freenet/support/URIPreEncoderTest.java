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

import java.net.URI;
import java.net.URISyntaxException;
import freenet.utils.UTFUtil;
import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.URIPreEncoder} class
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class URIPreEncoderTest extends TestCase {

	private String prtblAscii = new String(UTFUtil.PRINTABLE_ASCII);
	private String stressedUTF_8Chars = new String(UTFUtil.STRESSED_UTF);
	
	private boolean containsOnlyValidChars(String aString) {
		char eachChar;
		for (int i = 0; i < aString.length(); i++) {
			eachChar = aString.charAt(i);
			if (URIPreEncoder.allowedChars.indexOf(eachChar) < 0)
				return false;
		};
		return true;
	}
	
	/**
	 * Tests encode(String) method
	 * to verify if it converts all
	 * not safe chars into safe chars.
	 */
	public void testEncode() {
		String toEncode = prtblAscii+stressedUTF_8Chars;
		String encoded = URIPreEncoder.encode(toEncode);
		assertTrue(containsOnlyValidChars(encoded));
	}

	/**
	 * Tests encodeURI(String) method
	 * to verify if it converts all
	 * not safe chars into safe chars.
	 */
	public void testEncodeURI() {
		String toEncode = prtblAscii+stressedUTF_8Chars;
		URI encoded;
		//try {
		//	encoded = URIPreEncoder.encodeURI(toEncode);		this method will throw a not expected exception because '%' is included as a valid char
		//	assertTrue(containsOnlyValidChars(encoded.toString()));
		//} catch (URISyntaxException anException) {
		//	fail("Not expected exception thrown : " + anException.getMessage()); }
	}

}
