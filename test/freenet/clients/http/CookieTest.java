/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import static freenet.clients.http.Cookie.COOKIE_TOKEN_SEPARATOR_CHARACTERS;
import static freenet.clients.http.Cookie.COOKIE_VALUE_FORBIDDEN_CHARS;
import static org.junit.Assert.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import org.junit.function.ThrowingRunnable;

public class CookieTest {

	private static final String VALID_PATH = "/Freetalk";
	private static final String VALID_NAME = "SessionID";
	private static final String VALID_VALUE = "abCd12345";


	/**
	 * https://www.ietf.org/rfc/rfc2965.html#section-3.2
	 */
	private static final Set<String> RESERVED_COOKIE_MEMBERS = Collections.unmodifiableSet(
		new HashSet<>(
			Arrays.asList(
				"Comment",
				"CommentURL",
				"Discard",
				"Domain",
				"Max-Age",
				"Path",
				"Port",
				"Secure",
				"Version"
			)
		)
	);

	/**
	 * Sample control characters
	 */
	private static final Set<Character> SOME_CONTROL_CHARACTERS = Collections.unmodifiableSet(
		new HashSet<>(
			Arrays.asList(
				(char) 127, // DEL
				(char) 27, // ESC
				(char) 8, // backspace
				'\n',
				'\r',
				'\t',
				(char) 0
			)
		)
	);

	/**
	 * https://www.ietf.org/rfc/rfc2396.html#section-2.2
	 */
	private static final Set<Character> URI_RESERVED_CHARACTERS = Collections.unmodifiableSet(
		new HashSet<>(
			Arrays.asList(
				';', '/', '?', ':', ':', '@', '&', '=', '+', '$', ','
			)
		)
	);
	/**
	 * https://www.ietf.org/rfc/rfc2396.html#section-2.3
	 */
	private static final Set<Character> URI_UNRESERVED_CHARACTERS = Collections.unmodifiableSet(
		new HashSet<>(
			Arrays.asList(
				'a', 'A', '7', '-', '_', '.', '!', '~', '*', '\'', '(', ')'
			)
		)
	);

	private static final Set<String> VALID_TOKENS = Collections.unmodifiableSet(
		new HashSet<>(
			Arrays.asList(
				"my-name",
				"-name",
				"name-",
				"my_name",
				"_name",
				"name_",
				"my+name",
				"+name",
				"name+",
				"my.name",
				".name",
				"name.",
				" name "
			)
		)
	);

	URI validPath;
	Instant validExpiresTime;
	
	Cookie cookie;
	
	@Before
	public void setUp() throws Exception {
		validPath = new URI(VALID_PATH);
		validExpiresTime = Instant.now().plusMillis(+60*60*1000);
		cookie = new Cookie(validPath, VALID_NAME, VALID_VALUE, validExpiresTime);
	}

	@Test
	public void testCookieURIParameter() throws Exception {
		assertThrowsIllegalArgumentException(
			"Constructor allows path to be null",
			() -> new Cookie(null, VALID_NAME, VALID_VALUE, validExpiresTime)
		);
		assertThrowsIllegalArgumentException(
			"Constructor allows path to be empty",
			() -> new Cookie(new URI(""), VALID_NAME, VALID_VALUE, validExpiresTime)
		);

		for (Character c : URI_RESERVED_CHARACTERS) {
			new Cookie(new URI("/my" + c + "/value"), VALID_NAME, VALID_VALUE, validExpiresTime);
		}

		for (Character c : URI_UNRESERVED_CHARACTERS) {
			new Cookie(new URI("/my" + c + "/value"), VALID_NAME, VALID_VALUE, validExpiresTime);
		}

		assertThrowsIllegalArgumentException(
			"Constructor allows path not starting with /",
			() -> new Cookie(new URI("my/path"), VALID_NAME, VALID_VALUE, validExpiresTime)
		);

		assertThrowsIllegalArgumentException(
			"Constructor allows path containing full URI",
			() -> new Cookie(new URI("http://example.com/my/path"), VALID_NAME, VALID_VALUE, validExpiresTime)
		);

		for (String s : Arrays.asList("free%20net", "net%09free")) {
			new Cookie(new URI("/" + s), VALID_NAME, VALID_VALUE, validExpiresTime);
		}
	}

	@Test
	public void testCookieNameParameter() {
		assertThrowsIllegalArgumentException(
			"Constructor allows name to be null",
			() -> cookieForName(null)
		);

		assertThrowsIllegalArgumentException(
			"Constructor allows name to be empty",
			() -> cookieForName("")
		);

		assertThrowsIllegalArgumentException(
			"Constructor allows invalid characters in name",
			() -> cookieForName("test;")
		);

		for (String reservedName: RESERVED_COOKIE_MEMBERS) {
			assertInvalidCookieAttributeName(reservedName);
			assertInvalidCookieAttributeName(reservedName.toLowerCase(Locale.ROOT));
			assertInvalidCookieAttributeName(reservedName.toUpperCase(Locale.ROOT));

			for (int i = 0; i < reservedName.length(); i++) {
				char[] chars = reservedName.toCharArray();
				char c = chars[i];
				chars[i] = Character.toString(c).toLowerCase(Locale.ROOT).charAt(0);

				String nameWithLowerChar = new String(chars);
				assertInvalidCookieAttributeName(nameWithLowerChar);

				chars[i] = Character.toString(c).toUpperCase(Locale.ROOT).charAt(0);
				String nameWithUpperChar = new String(chars);
				assertInvalidCookieAttributeName(nameWithUpperChar);
			}
		}

		for (Character c : COOKIE_TOKEN_SEPARATOR_CHARACTERS) {
			assertInvalidCookieAttributeName("separator" + c + "name");
		}
		for (Character c : COOKIE_VALUE_FORBIDDEN_CHARS) {
			assertInvalidCookieAttributeName("separator" + c + "name");
		}
		for (Character c : SOME_CONTROL_CHARACTERS) {
			assertInvalidCookieAttributeName("control" + c + "name");
		}
		// US-ASCII only
		assertInvalidCookieAttributeName("cöölName");

		for (String validToken : VALID_TOKENS) {
			cookieForName(validToken);
		}
	}

	private Cookie cookieForName(String name) {
		return new Cookie(validPath, name, VALID_VALUE, validExpiresTime);
	}

	private void assertInvalidCookieAttributeName(String name) {
		assertThrowsIllegalArgumentException(
			"Constructor allows invalid cookie attribute name: '" + name + "'",
			() -> cookieForName(name)
		);
	}

	@Test
	public void testCookieValueParameter() {

		// Empty values are allowed;
		assertEquals("", cookieForValue(null).getValue());
		assertEquals("", cookieForValue("").getValue());

		assertThrowsIllegalArgumentException(
			"Constructor allows invalid characters in value",
			() -> cookieForValue("\"")
		);

		assertThrowsIllegalArgumentException(
			"Constructor allows non-US-ASCII characters in value",
			() -> cookieForValue(VALID_VALUE + "ä")
		);

		for (String cookieMember : RESERVED_COOKIE_MEMBERS) {
			// allow reserved words as values
			cookieForValue(cookieMember);
		}

		for (Character c : COOKIE_VALUE_FORBIDDEN_CHARS) {
			assertInvalidCookieAttributeValue("separator" + c + "value");
		}
		for (Character c : SOME_CONTROL_CHARACTERS) {
			assertInvalidCookieAttributeValue("control" + c + "value");
		}
		// US-ASCII only
		assertInvalidCookieAttributeValue("cöölValue");

		for (String validToken : VALID_TOKENS) {
			new Cookie(validPath, validToken, VALID_VALUE, validExpiresTime);
		}
		for (String validName : Arrays.asList(
			"my name",
			" my name "
		)) {
			cookieForValue(validName);
		}
	}

	private Cookie cookieForValue(String myValue) {
		return new Cookie(validPath, VALID_NAME, myValue, validExpiresTime);
	}

	private void assertInvalidCookieAttributeValue(String value) {
		assertThrowsIllegalArgumentException(
			"Constructor allows invalid cookie attribute value: '" + value + "'",
			() -> cookieForValue(value)
		);
	}

	@Test
	public void testCookieDateParameter() {
		assertThrowsIllegalArgumentException(
			"Constructor allows construction with null date.",
			() -> new Cookie(validPath, VALID_NAME, VALID_VALUE, null)
		);

		new Cookie(validPath, VALID_NAME, VALID_VALUE, Instant.now().minusMillis(-1));
	}

	@Test
	public void testEqualsObject() throws URISyntaxException {
		assertEquals(cookie, cookie);
		assertEquals(cookie, new Cookie(validPath, VALID_NAME, VALID_VALUE, Instant.now().plusMillis(60*1000)));
		
		// Value is not checked in equals().
		assertEquals(cookie, new Cookie(validPath, VALID_NAME, "", Instant.now().plusMillis(60*1000)));

		assertNotEquals(cookie, new Cookie(new URI(VALID_PATH.toLowerCase()), VALID_NAME, VALID_VALUE, validExpiresTime));
		assertEquals(cookie, new Cookie(validPath, VALID_NAME.toLowerCase(), VALID_VALUE, validExpiresTime));

		assertNotEquals(cookie, new Object());
		assertNotEquals(cookieForName("first"), cookieForName("second"));

		// TODO: Test domain. This is currently done in ReceivedCookieTest
	}

	@Test
	public void testHashCodeMethod() {
		MatcherAssert.assertThat(cookie.hashCode(), Matchers.any(Integer.class));
	}

	@Test
	public void testGetDomain() {
		assertNull(cookie.getDomain());
		// TODO: Implement.
	}

	@Test
	public void testGetPath() {
		assertEquals(VALID_PATH, cookie.getPath().toString());
	}

	@Test
	public void testGetName() {
		assertEquals(VALID_NAME.toLowerCase(), cookie.getName());
	}

	@Test
	public void testGetValue() {
		assertEquals(VALID_VALUE, cookie.getValue());
	}

	@Test
	public void testGetExpirationDate() {
		assertEquals(validExpiresTime, cookie.getExpirationTime());
	}

	@Test
	public void testEncodeToHeaderValue() {
		String headerValue = cookie.encodeToHeaderValue();

		assertNotNull(headerValue);
		assertFalse(headerValue.isEmpty());

		assertTrue(headerValue.contains(String.format("%s=%s;", VALID_NAME.toLowerCase(), VALID_VALUE)));
		assertTrue(headerValue.contains("version=1;"));
		assertTrue(headerValue.contains(String.format("path=%s;", validPath.getRawPath())));

		String expireTimestampStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(validExpiresTime.atZone(ZoneOffset.UTC));
		assertTrue(headerValue.contains(String.format("expires=%s;", expireTimestampStr)));

		assertTrue(headerValue.contains("discard=true;"));
	}

	@Test
	public void encodeToHeaderValueCanEncodeValueWithSpaces() {
		String strWithSpace = "test space";
		Cookie cookie = cookieForValue(strWithSpace);
		assertEquals(strWithSpace, cookie.getValue());
		String encoded = cookie.encodeToHeaderValue();
		assertTrue(encoded.contains(String.format("%s=%s;", VALID_NAME.toLowerCase(), strWithSpace)));
	}

	protected static void assertThrowsIllegalArgumentException(String message, ThrowingRunnable runnable) {
		assertThrows(message, IllegalArgumentException.class, runnable);
	}
}
