/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.net.URI;

import junit.framework.TestCase;
import freenet.l10n.NodeL10n;
import freenet.support.Logger;
import freenet.support.api.BucketFactory;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;

/**
 * A simple meta-test to track regressions of the content-filter
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class ContentFilterTest extends TestCase {
	private static final String BASE_URI_PROTOCOL = "http";
	private static final String BASE_URI_CONTENT = "localhost:8888";
	private static final String BASE_KEY = "USK@0I8gctpUE32CM0iQhXaYpCMvtPPGfT4pjXm01oid5Zc,3dAcn4fX2LyxO6uCnWFTx-2HKZ89uruurcKwLSCxbZ4,AQACAAE/Ultimate-Freenet-Index/55/";
	private static final String BASE_URI = BASE_URI_PROTOCOL+"://"+BASE_URI_CONTENT+'/';
	private static final String ALT_BASE_URI = BASE_URI_PROTOCOL+"://"+BASE_URI_CONTENT+'/'+BASE_KEY;
	
	private static final String EXTERNAL_LINK = "www.evilwebsite.gov";
	private static final String EXTERNAL_LINK_OK = "<a />";
	// check that external links are not allowed
	private static final String EXTERNAL_LINK_CHECK1 = "<a href=\""+EXTERNAL_LINK+"\"/>";
	private static final String EXTERNAL_LINK_CHECK2 = "<a href=\""+BASE_URI_PROTOCOL+"://"+EXTERNAL_LINK+"\"/>";
	private static final String EXTERNAL_LINK_CHECK3 = "<a href=\""+BASE_URI_CONTENT+"@http://"+EXTERNAL_LINK+"\"/>";
	
	private static final String INTERNAL_RELATIVE_LINK = "<a href=\"/KSK@gpl.txt\" />";
	private static final String INTERNAL_ABSOLUTE_LINK = "<a href=\""+BASE_URI+"KSK@gpl.txt\" />";
	
	private static final String INTERNAL_RELATIVE_LINK1 = "<a href=\"test.html\" />";
	
	// @see bug #710
	private static final String ANCHOR_TEST = "<a href=\"#test\" />";
	private static final String ANCHOR_TEST_EMPTY = "<a href=\"#\" />";
	private static final String ANCHOR_TEST_SPECIAL = "<a href=\"#!$()*+,;=:@ABC0123-._~xyz%3f\" />"; // RFC3986 / RFC 2396
	private static final String ANCHOR_TEST_SPECIAL2 = "<a href=\"#!$&'()*+,;=:@ABC0123-._~xyz%3f\" />";
	private static final String ANCHOR_TEST_SPECIAL2_RESULT = "<a href=\"#!$&amp;&#39;()*+,;=:@ABC0123-._~xyz%3f\" />"; 
	
	// @see bug #2496
	private static final String ANCHOR_RELATIVE1 = "<a href=\"/KSK@test/test.html#C2\">";
	private static final String ANCHOR_RELATIVE2 = "<a href=\"/KSK@test/path/test.html#C2\">";
	private static final String ANCHOR_FALSE_POS1 = "<a href=\"/KSK@test/path/test.html#%23\">"; // yes, this is valid
	private static final String ANCHOR_FALSE_POS2 = "<a href=\"/KSK@test/path/%23.html#2\">"; // yes, this is valid too

	// evil hack for #2496 + #2451, <SPACE><#> give <SPACE><%23>
	private static final String ANCHOR_MIXED = "<a href=\"/KSK@test/path/music #1.ogg\">";
	private static final String ANCHOR_MIXED_RESULT = "<a href=\"/KSK@test/path/music%20%231.ogg\">";
	
	// @see bug #2451
	private static final String POUNT_CHARACTER_ENCODING_TEST = "<a href=\"/CHK@DUiGC5D1ZsnFpH07WGkNVDujNlxhtgGxXBKrMT-9Rkw,~GrAWp02o9YylpxL1Fr4fPDozWmebhGv4qUoFlrxnY4,AAIC--8/Testing - [blah] Apostrophe' - gratuitous 1 AND CAPITAL LETTERS!!!!.ogg\" />";
	private static final String POUNT_CHARACTER_ENCODING_TEST_RESULT = "<a href=\"/CHK@DUiGC5D1ZsnFpH07WGkNVDujNlxhtgGxXBKrMT-9Rkw,~GrAWp02o9YylpxL1Fr4fPDozWmebhGv4qUoFlrxnY4,AAIC--8/Testing%20-%20%5bblah%5d%20Apostrophe%27%20-%20gratuitous%201%20AND%20CAPITAL%20LETTERS%21%21%21%21.ogg\" />";
	// @see bug #2297
	private static final String PREVENT_FPROXY_ACCESS = "<a href=\""+BASE_URI+"\"/>";
	// @see bug #2921
	private static final String PREVENT_EXTERNAL_ACCESS_CSS_SIMPLE = "<style>div { background: url("+BASE_URI+") }</style>";
	private static final String PREVENT_EXTERNAL_ACCESS_CSS_CASE = "<style>div { background: uRl("+BASE_URI+") }</style>";
	private static final String PREVENT_EXTERNAL_ACCESS_CSS_ESCAPE = "<style>div { background: \\u\\r\\l("+BASE_URI+") }</style>";
	private static final String WHITELIST_STATIC_CONTENT = "<a href=\"/static/themes/clean/theme.css\" />";
	private static final String XHTML_VOIDELEMENT="<html xmlns=\"http://www.w3.org/1999/xhtml\"><br><hr></html>";
	private static final String XHTML_VOIDELEMENTC="<html xmlns=\"http://www.w3.org/1999/xhtml\"><br /><hr /></html>";
	private static final String XHTML_INCOMPLETEDOCUMENT="<html xmlns=\"http://www.w3.org/1999/xhtml\"><body> <h1> helloworld <h2> helloworld";
	private static final String XHTML_INCOMPLETEDOCUMENTC="<html xmlns=\"http://www.w3.org/1999/xhtml\"><body> <h1> helloworld <h2> helloworld</h2></h1></body></html>";
	private static final String XHTML_IMPROPERNESTING="<html xmlns=\"http://www.w3.org/1999/xhtml\"><b><i>helloworld</b></i></html>";
	private static final String XHTML_IMPROPERNESTINGC="<html xmlns=\"http://www.w3.org/1999/xhtml\"><b><i>helloworld</i></b></html>";
	
	private static final String CSS_STRING_NEWLINES = "<style>* { content: \"this string does not terminate\n}\nbody {\nbackground: url(http://www.google.co.uk/intl/en_uk/images/logo.gif); }\n\" }</style>";
	private static final String CSS_STRING_NEWLINESC = "<style>* {}\nbody { }\n</style>";

	private static final String HTML_STYLESHEET_MAYBECHARSET = "<link rel=\"stylesheet\" href=\"test.css\">";
	private static final String HTML_STYLESHEET_MAYBECHARSETC = "<link rel=\"stylesheet\" type=\"text/css\" href=\"test.css?type=text/css&amp;maybecharset=iso-8859-1\">";
	
	private static final String HTML_STYLESHEET_CHARSET = "<link rel=\"stylesheet\" charset=\"utf-8\" href=\"test.css\">";
	private static final String HTML_STYLESHEET_CHARSETC = "<link charset=\"utf-8\" rel=\"stylesheet\" type=\"text/css\" href=\"test.css?type=text/css%3b%20charset=utf-8\">";

	private static final String HTML_STYLESHEET_CHARSET_BAD = "<link rel=\"stylesheet\" charset=\"utf-8&max-size=4194304\" href=\"test.css\">";
	private static final String HTML_STYLESHEET_CHARSET_BADC = "<link rel=\"stylesheet\" type=\"text/css\" href=\"test.css?type=text/css&amp;maybecharset=iso-8859-1\">";
	
	private static final String HTML_STYLESHEET_CHARSET_BAD1 = "<link rel=\"stylesheet\" type=\"text/css; charset=utf-8&max-size=4194304\" href=\"test.css\">";
	private static final String HTML_STYLESHEET_CHARSET_BAD1C = "<link rel=\"stylesheet\" type=\"text/css\" href=\"test.css?type=text/css&amp;maybecharset=iso-8859-1\">";
	
	private static final String FRAME_SRC_CHARSET = "<frame src=\"test.html?type=text/html; charset=UTF-8\">";
	private static final String FRAME_SRC_CHARSETC = "<frame src=\"test.html?type=text/html%3b%20charset=UTF-8\">";
	
	private static final String FRAME_SRC_CHARSET_BAD = "<frame src=\"test.html?type=text/html; charset=UTF-8&max-size=4194304\">";
	private static final String FRAME_SRC_CHARSET_BADC = "<frame src=\"test.html?type=text/html%3b%20charset=UTF-8\">";
	
	private static final String FRAME_SRC_CHARSET_BAD1 = "<frame src=\"test.html?type=text/html; charset=UTF-8%26max-size=4194304\">";
	private static final String FRAME_SRC_CHARSET_BAD1C = "<frame src=\"test.html?type=text/html\">";
	
	private static final String SPAN_WITH_STYLE = "<span style=\"font-family: verdana, sans-serif; color: red;\">";
	
	// From CSS spec
	
	private static final String CSS_SPEC_EXAMPLE1 = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\">\n<HTML>\n  <HEAD>\n  <TITLE>Bach's home page</TITLE>\n  <STYLE type=\"text/css\">\n    body {\n      font-family: \"Gill Sans\", sans-serif;\n      font-size: 12pt;\n      margin: 3em;\n\n    }\n  </STYLE>\n  </HEAD>\n  <BODY>\n    <H1>Bach's home page</H1>\n    <P>Johann Sebastian Bach was a prolific composer.\n  </BODY>\n</HTML>";
	
	private final BucketFactory bf = new ArrayBucketFactory();

	public void testHTMLFilter() throws Exception {
		new NodeL10n();
		
    	Logger.setupStdoutLogging(Logger.MINOR, "freenet.clients.http.filter.Generic:DEBUG");
		
		// General sanity checks
		// is "relativization" working?
		assertEquals(INTERNAL_RELATIVE_LINK, HTMLFilter(INTERNAL_RELATIVE_LINK));
		assertEquals(INTERNAL_RELATIVE_LINK, HTMLFilter(INTERNAL_RELATIVE_LINK, true));
		assertEquals(INTERNAL_RELATIVE_LINK1, HTMLFilter(INTERNAL_RELATIVE_LINK1, true));
		assertEquals(INTERNAL_RELATIVE_LINK, HTMLFilter(INTERNAL_ABSOLUTE_LINK));
		// are external links stripped out ?
		assertTrue(HTMLFilter(EXTERNAL_LINK_CHECK1).startsWith(EXTERNAL_LINK_OK));
		assertTrue(HTMLFilter(EXTERNAL_LINK_CHECK2).contains(GenericReadFilterCallback.magicHTTPEscapeString));
		assertTrue(HTMLFilter(EXTERNAL_LINK_CHECK3).startsWith(EXTERNAL_LINK_OK));
		
		// regression testing 
		// bug #710
		assertEquals(ANCHOR_TEST, HTMLFilter(ANCHOR_TEST));
		assertEquals(ANCHOR_TEST_EMPTY, HTMLFilter(ANCHOR_TEST_EMPTY));
		assertEquals(ANCHOR_TEST_SPECIAL, HTMLFilter(ANCHOR_TEST_SPECIAL));
		assertEquals(ANCHOR_TEST_SPECIAL2_RESULT, HTMLFilter(ANCHOR_TEST_SPECIAL2));
		// bug #2496
		assertEquals(ANCHOR_RELATIVE1, HTMLFilter(ANCHOR_RELATIVE1));
		assertEquals(ANCHOR_RELATIVE2, HTMLFilter(ANCHOR_RELATIVE2));
		assertEquals(ANCHOR_FALSE_POS1, HTMLFilter(ANCHOR_FALSE_POS1));
		assertEquals(ANCHOR_FALSE_POS2, HTMLFilter(ANCHOR_FALSE_POS2));
		// EVIL HACK TEST for #2496 + #2451
		assertEquals(ANCHOR_MIXED_RESULT, HTMLFilter(ANCHOR_MIXED));
		// bug #2451
		assertEquals(POUNT_CHARACTER_ENCODING_TEST_RESULT, HTMLFilter(POUNT_CHARACTER_ENCODING_TEST));
		// bug #2297
		assertTrue(HTMLFilter(PREVENT_FPROXY_ACCESS).contains(GenericReadFilterCallback.magicHTTPEscapeString));
		// bug #2921
		assertTrue(HTMLFilter(PREVENT_EXTERNAL_ACCESS_CSS_SIMPLE).contains("div {}"));
		assertTrue(HTMLFilter(PREVENT_EXTERNAL_ACCESS_CSS_ESCAPE).contains("div {}"));
		assertTrue(HTMLFilter(PREVENT_EXTERNAL_ACCESS_CSS_CASE).contains("div {}"));
		assertEquals(WHITELIST_STATIC_CONTENT, HTMLFilter(WHITELIST_STATIC_CONTENT));
		assertEquals(XHTML_VOIDELEMENTC,HTMLFilter(XHTML_VOIDELEMENT));
		assertEquals(XHTML_INCOMPLETEDOCUMENTC,HTMLFilter(XHTML_INCOMPLETEDOCUMENT));
		assertEquals(XHTML_IMPROPERNESTINGC,HTMLFilter(XHTML_IMPROPERNESTING));
		
		assertEquals(CSS_STRING_NEWLINESC,HTMLFilter(CSS_STRING_NEWLINES));
		
		assertEquals(HTML_STYLESHEET_MAYBECHARSETC, HTMLFilter(HTML_STYLESHEET_MAYBECHARSET, true));
		assertEquals(HTML_STYLESHEET_CHARSETC, HTMLFilter(HTML_STYLESHEET_CHARSET, true));
		assertEquals(HTML_STYLESHEET_CHARSET_BADC, HTMLFilter(HTML_STYLESHEET_CHARSET_BAD, true));
		assertEquals(HTML_STYLESHEET_CHARSET_BAD1C, HTMLFilter(HTML_STYLESHEET_CHARSET_BAD1, true));
		
		assertEquals(FRAME_SRC_CHARSETC, HTMLFilter(FRAME_SRC_CHARSET, true));
		assertEquals(FRAME_SRC_CHARSET_BADC, HTMLFilter(FRAME_SRC_CHARSET_BAD, true));
		assertEquals(FRAME_SRC_CHARSET_BAD1C, HTMLFilter(FRAME_SRC_CHARSET_BAD1, true));
		
		assertEquals(CSS_SPEC_EXAMPLE1, HTMLFilter(CSS_SPEC_EXAMPLE1));
		
		assertEquals(SPAN_WITH_STYLE, HTMLFilter(SPAN_WITH_STYLE));
	}
		
	private String HTMLFilter(String data) throws Exception {
		return HTMLFilter(data, false);
	}
	
	private String HTMLFilter(String data, boolean alt) throws Exception {
		String typeName = "text/html";
		URI baseURI = new URI(alt ? ALT_BASE_URI : BASE_URI);
		byte[] dataToFilter = data.getBytes("UTF-8");
		
		return ContentFilter.filter(new ArrayBucket(dataToFilter), bf, typeName, baseURI, null, null).data.toString();
	}
}
