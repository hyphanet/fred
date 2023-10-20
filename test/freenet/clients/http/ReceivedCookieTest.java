/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import static org.junit.Assert.*;

import java.text.ParseException;
import java.util.Arrays;
import java.util.List;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class ReceivedCookieTest {

	private static final String validEncodedCookie = " SessionID = \"abCd12345\" ;"
		+ " $Version = 1 ;"
		+ " $Path = \"/Freetalk\";"
		+ " $Discard; "
		+ " $Expires = \"Fri, 25 Oct 2030 15:09:37 GMT\"; "
		+ " $blah;";

	private static final String VALID_NAME = "SessionID";
	private static final String VALID_VALUE = "abCd12345";

	private ReceivedCookie cookie;

	@Before
	public void setUp() throws Exception {
		cookie = parseHeaderAndGetFirst(validEncodedCookie);
	}

	@Test
	public void testParseHeader() throws ParseException {
		// The tests for getPath(), getName() etc will be executed using the parsed mCookie and therefore also test parseHeader() for valid values,
		// we only need to test special cases here.
		
		List<ReceivedCookie> cookies;
		ReceivedCookie cookie;
		
		// Plain firefox cookie

		cookie = parseHeaderAndGetFirst("SessionID=abCd12345");
		assertEquals(VALID_NAME, cookie.getName());
		assertEquals(VALID_VALUE, cookie.getValue());
		
		// Two plain firefox cookies
		
		cookies = ReceivedCookie.parseHeader("SessionID=abCd12345;key2=valUe2");
		cookie = cookies.get(0);
		assertEquals(VALID_NAME, cookie.getName());
		assertEquals(VALID_VALUE, cookie.getValue());

		cookie = cookies.get(1);
		assertEquals("key2", cookie.getName());
		assertEquals("valUe2", cookie.getValue());
	}

	@Test
	public void canParseKeyOnly() throws ParseException {
		ReceivedCookie cookie = parseHeaderAndGetFirst("$blah");
		assertEquals("$blah", cookie.getName());
		assertNull(cookie.getValue());
	}

	@Test
	public void canParseKeyWithoutValue() throws ParseException {
		List<ReceivedCookie> cookies = ReceivedCookie.parseHeader(" SessionID = \"abCd12345\" ; $blah;");
		assertEquals(2, cookies.size());

		ReceivedCookie cookie = cookies.get(0);
		assertEquals(VALID_NAME, cookie.getName());
		assertEquals(VALID_VALUE, cookie.getValue());

		cookie = cookies.get(1);
		assertEquals("$blah", cookie.getName());
		assertNull(cookie.getValue());
	}

	@Test
	public void canParseKeyWithoutValueAndSemicolonAtTheEnd() throws ParseException {
		List<ReceivedCookie> cookies = ReceivedCookie.parseHeader(" SessionID = \"abCd12345\" ; $blah");
		assertEquals(2, cookies.size());
		ReceivedCookie cookie = cookies.get(0);
		assertEquals(VALID_NAME, cookie.getName());
		assertEquals(VALID_VALUE, cookie.getValue());
		cookie = cookies.get(1);
		assertEquals("$blah", cookie.getName());
		assertNull(cookie.getValue());
	}

	@Test
	public void canParseEmptyString() throws ParseException {
		for (String empty: Arrays.asList(
			null,
			"",
			" ",
			"\n",
			"\t",
			" \t \n"
		)) {
			List<ReceivedCookie> cookies = ReceivedCookie.parseHeader(empty);
			assertNotNull(cookies);
			assertEquals(0, cookies.size());
		}
	}

	@Test
	public void testEqualsMethod() throws ParseException {
		assertEquals(cookie, cookie);
		assertEquals(parseHeaderAndGetFirst(validEncodedCookie), parseHeaderAndGetFirst(validEncodedCookie));
		assertEquals(cookie, parseHeaderAndGetFirst(validEncodedCookie));
	}

	@Test
	public void testHashCodeMethod() {
		MatcherAssert.assertThat(cookie.hashCode(), Matchers.any(Integer.class));
	}

	private static ReceivedCookie parseHeaderAndGetFirst(String cookieValue) throws ParseException {
		return ReceivedCookie.parseHeader(cookieValue).get(0);
	}
}
