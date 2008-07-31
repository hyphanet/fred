/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import freenet.support.api.BucketFactory;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import java.net.URI;
import junit.framework.TestCase;

/**
 * A simple meta-test to track regressions of the content-filter
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class ContentFilterTest extends TestCase {
	private static final String BASE_URI_PROTOCOL = "http";
	private static final String BASE_URI_CONTENT = "localhost:8888";
	private static final String BASE_URI = BASE_URI_PROTOCOL+"://"+BASE_URI_CONTENT+'/';
	
	private static final String EXTERNAL_LINK = "www.evilwebsite.gov";
	private static final String EXTERNAL_LINK_OK = "<a />";
	// check that external links are not allowed
	private static final String EXTERNAL_LINK_CHECK1 = "<a href=\""+EXTERNAL_LINK+"\"/>";
	private static final String EXTERNAL_LINK_CHECK2 = "<a href=\""+BASE_URI_PROTOCOL+"://"+EXTERNAL_LINK+"\"/>";
	private static final String EXTERNAL_LINK_CHECK3 = "<a href=\""+BASE_URI_CONTENT+"@http://"+EXTERNAL_LINK+"\"/>";
	
	private static final String INTERNAL_RELATIVE_LINK = "<a href=\"/KSK@gpl.txt\" />";
	private static final String INTERNAL_ABSOLUTE_LINK = "<a href=\""+BASE_URI+"KSK@gpl.txt\" />";
	
	// @see bug #710
	private static final String ANCHOR_TEST = "<a href=\"#test\" />";
	// @see bug #2451
	private static final String POUNT_CHARACTER_ENCODING_TEST = "<a href=\"/CHK@abcdefghijklmnopqrstuvwxyz-_1234567890,AAEC--8/Testing - [blah] Apostrophe' - gratuitous #1 AND CAPITAL LETTERS!!!!.ogg\" />";
	private static final String POUNT_CHARACTER_ENCODING_TEST_RESULT = "<a href=\"/CHK@abcdefghijklmnopqrstuvwxyz-_1234567890%2cAAEC--8/Testing%20-%20%5bblah%5d%20Apostrophe%27%20-%20gratuitous%20%231%20AND%20CAPITAL%20LETTERS%21%21%21%21.ogg\" />";
	// @see bug #2297
	private static final String PREVENT_FPROXY_ACCESS = "<a href=\""+BASE_URI+"\"/>";
	private static final String WHITELIST_STATIC_CONTENT = "<a href=\"/static/themes/clean/theme.css\" />";

	private final BucketFactory bf = new ArrayBucketFactory();

	public void testHTMLFilter() throws Exception {
		// General sanity checks
		// is "relativization" working?
		assertEquals(INTERNAL_RELATIVE_LINK, HTMLFilter(INTERNAL_RELATIVE_LINK));
		assertEquals(INTERNAL_RELATIVE_LINK, HTMLFilter(INTERNAL_ABSOLUTE_LINK));
		// are external links stripped out ?
		assertTrue(HTMLFilter(EXTERNAL_LINK_CHECK1).startsWith(EXTERNAL_LINK_OK));
		assertTrue(HTMLFilter(EXTERNAL_LINK_CHECK2).contains(GenericReadFilterCallback.magicHTTPEscapeString));
		assertTrue(HTMLFilter(EXTERNAL_LINK_CHECK3).startsWith(EXTERNAL_LINK_OK));
		
		// regression testing
		assertEquals(ANCHOR_TEST, HTMLFilter(ANCHOR_TEST));
		
		assertEquals(POUNT_CHARACTER_ENCODING_TEST_RESULT, HTMLFilter(POUNT_CHARACTER_ENCODING_TEST));
		
		assertTrue(HTMLFilter(PREVENT_FPROXY_ACCESS).contains(GenericReadFilterCallback.magicHTTPEscapeString));
		assertEquals(WHITELIST_STATIC_CONTENT, HTMLFilter(WHITELIST_STATIC_CONTENT));
	}
		
	private String HTMLFilter(String data) throws Exception {
		String typeName = "text/html";
		URI baseURI = new URI(BASE_URI);
		
		return ContentFilter.filter(new ArrayBucket(data.getBytes("UTF-8")), bf, typeName, baseURI, null).data.toString();
	}
}
