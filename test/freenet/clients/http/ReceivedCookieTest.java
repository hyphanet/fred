/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;

public class ReceivedCookieTest extends CookieTest {
	
	static final String validEncodedCookie = " SessionID = \"abCd12345\" ;"
											+ " $Version = 1 ;"
											+ " $Path = \"/Freetalk\";"
											+ " $Discard; "
											+ " $Expires = \"Sun, 25 Oct 2030 15:09:37 GMT\"; "
											+ " $blah;";

	@Override
	@SuppressWarnings("deprecation")
	protected void setUp() throws Exception { 		
		super.setUp();
		
		validExpiresDate = new Date(2030 - 1900, 10 - 1, 25, 15, 9, 37);
		
		cookie = ReceivedCookie.parseHeader(validEncodedCookie).get(0);
	}

	@Override
	public void testGetDomain() {
		// TODO: Implement.
	}

	public void testParseHeader() throws ParseException {
		// The tests for getPath(), getName() etc will be executed using the parsed mCookie and therefore also test parseHeader() for valid values,
		// we only need to test special cases here.
		
		ArrayList<ReceivedCookie> cookies;
		Cookie cookie;
		
		// Plain firefox cookie
		
		cookie = ReceivedCookie.parseHeader("SessionID=abCd12345").get(0);
		assertEquals(VALID_NAME.toLowerCase(), cookie.getName()); assertEquals(VALID_VALUE, cookie.getValue());
		
		// Two plain firefox cookies
		
		cookies = ReceivedCookie.parseHeader("SessionID=abCd12345;key2=valUe2");
		cookie = cookies.get(0); assertEquals(VALID_NAME.toLowerCase(), cookie.getName()); assertEquals(VALID_VALUE, cookie.getValue());
		cookie = cookies.get(1); assertEquals("key2", cookie.getName()); assertEquals("valUe2", cookie.getValue());
		
		// Key without value at end:
		
		cookie = ReceivedCookie.parseHeader(" SessionID = \"abCd12345\" ;"
										+   " $blah;").get(0);
		assertEquals(VALID_NAME.toLowerCase(), cookie.getName()); assertEquals(VALID_VALUE, cookie.getValue());
		
		// Key without value and without semicolon at end
		cookie = ReceivedCookie.parseHeader(" SessionID = \"abCd12345\" ;"
										+   " $blah").get(0);
		assertEquals(VALID_NAME.toLowerCase(), cookie.getName()); assertEquals(VALID_VALUE, cookie.getValue());
	}

	@Override
	public void testEncodeToHeaderValue() {
		try {
			cookie.encodeToHeaderValue();
			fail("ReceivedCookie.encodeToHeaderValue() should throw UnsupportedOperationException!");
		} catch(UnsupportedOperationException e) {}
	}
}
