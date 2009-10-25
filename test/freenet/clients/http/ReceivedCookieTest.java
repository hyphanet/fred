package freenet.clients.http;

import java.util.Date;

public class ReceivedCookieTest extends CookieTest {
	
	static final String validEncodedCookie = " SessionID = \"abcd12345\" ;"
											+ " $Version = 1 ;"
											+ " $Path = \"/Freetalk\";"
											+ " $Discard; "
											+ " $Expires = \"Sun, 25 Oct 2030 15:09:37 GMT\"; "
											+ " $blah;";

	@SuppressWarnings("deprecation")
	protected void setUp() throws Exception { 		
		super.setUp();
		
		validExpiresDate = new Date(2030 - 1900, 10 - 1, 25, 15, 9, 37);
		
		cookie = ReceivedCookie.parseHeader(validEncodedCookie).get(0);
	}

	public void testGetDomain() {
		// TODO: Implement.
	}

	public void testParseHeader() {
		// The tests for getPath(), getName() etc will be executed using the parsed mCookie and therefore also test parseHeader() for valid values,
		// we only need to test special cases here.
	}

	public void testEncodeToHeaderValue() {
		try {
			cookie.encodeToHeaderValue();
			fail("ReceivedCookie.encodeToHeaderValue() should throw UnsupportedOperationException!");
		} catch(UnsupportedOperationException e) {}
	}
}
