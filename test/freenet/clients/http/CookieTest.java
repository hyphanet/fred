/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;

import junit.framework.TestCase;
import freenet.support.CurrentTimeUTC;

public class CookieTest extends TestCase {
	
	static final String VALID_PATH = "/Freetalk";
	static final String VALID_NAME = "SessionID";
	static final String VALID_VALUE = "abCd12345";
	
	URI validPath;
	Date validExpiresDate;
	
	Cookie cookie;
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
	
		validPath = new URI(VALID_PATH);
		validExpiresDate = new Date(CurrentTimeUTC.getInMillis()+60*60*1000);
		cookie = new Cookie(validPath, VALID_NAME, VALID_VALUE, validExpiresDate);
	}

	public void testCookieURIStringStringDate() throws URISyntaxException {
		try {
			new Cookie(null, VALID_NAME, VALID_VALUE, validExpiresDate);
			fail("Constructor allows path to be null");
		} catch(RuntimeException e) {}
		
		try {
			new Cookie(new URI(""), VALID_NAME, VALID_VALUE, validExpiresDate);
			fail("Constructor allows path to be empty");
		}
		catch(RuntimeException e) {}
		
		// TODO: Test for invalid characters in path.
		
		try {
			new Cookie(validPath, null, VALID_VALUE, validExpiresDate);
			fail("Constructor allows name to be null");
		} catch(RuntimeException e) {}
		
		try {
			new Cookie(validPath, "", VALID_VALUE, validExpiresDate);
			fail("Constructor allows name to be empty");
		} catch(RuntimeException e) {}
		
		try {
			new Cookie(validPath, "test;", VALID_VALUE, validExpiresDate);
			fail("Constructor allows invalid characters in name");
		} catch(RuntimeException e) {}
		
		// TODO: Test for more invalid characters in name

		// Empty values are allowed;
		new Cookie(validPath, VALID_NAME, null, validExpiresDate).getValue(); 
		new Cookie(validPath, VALID_NAME, "", validExpiresDate);
		
		try {
			new Cookie(validPath, VALID_NAME, "\"", validExpiresDate);
			fail("Constructor allows invalid characters in value");
		} catch(RuntimeException e) {}
		
		try {
			new Cookie(validPath, VALID_NAME, VALID_VALUE + "ä", validExpiresDate);
			fail("Constructor allows non-US-ASCII characters in value");
		} catch(RuntimeException e) {}
		
		// TODO: Test for more invalid characters in value;
		
		try {
			new Cookie(validPath, VALID_NAME, VALID_VALUE, new Date(CurrentTimeUTC.getInMillis()-1));
			fail("Constructor allows construction of expired cookies.");
		} catch(RuntimeException e) {}
	}

	public void testEqualsObject() throws URISyntaxException {
		assertEquals(cookie, cookie);
		assertEquals(cookie, new Cookie(validPath, VALID_NAME, VALID_VALUE, new Date(CurrentTimeUTC.getInMillis()+60*1000)));
		
		// Value is not checked in equals().
		assertEquals(cookie, new Cookie(validPath, VALID_NAME, "", new Date(CurrentTimeUTC.getInMillis()+60*1000)));
		
		assertFalse(cookie.equals(new Cookie(new URI(VALID_PATH.toLowerCase()), VALID_NAME, VALID_VALUE, validExpiresDate)));
		assertEquals(cookie, new Cookie(validPath, VALID_NAME.toLowerCase(), VALID_VALUE, validExpiresDate));
		
		// TODO: Test domain. This is currently done in ReceivedCookieTest
	}

	public void testGetDomain() {
		// TODO: Implement.
	}

	public void testGetPath() {
		assertEquals(VALID_PATH, cookie.getPath().toString());
	}

	public void testGetName() {
		assertEquals(VALID_NAME.toLowerCase(), cookie.getName());
	}

	public void testGetValue() {
		assertEquals(VALID_VALUE, cookie.getValue());
	}
	
// TODO: getExpirationDate() is commented out because it is broken, see ReceivedCookie.java
//	public void testGetExpirationDate() {
//		assertEquals(validExpiresDate, cookie.getExpirationDate());
//	}

	public void testEncodeToHeaderValue() {
		System.out.println(cookie.encodeToHeaderValue());
		
		// TODO: Implement.
	}

}
