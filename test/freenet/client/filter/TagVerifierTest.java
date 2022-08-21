/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import junit.framework.TestCase;

import java.net.URI;
import java.util.LinkedHashMap;

import freenet.client.filter.HTMLFilter.ParsedTag;
import freenet.client.filter.HTMLFilter.TagVerifier;

public class TagVerifierTest extends TestCase {
	private static final String BASE_URI_PROTOCOL = "http";
	private static final String BASE_URI_CONTENT = "localhost:8888";
	private static final String BASE_KEY = "USK@0I8gctpUE32CM0iQhXaYpCMvtPPGfT4pjXm01oid5Zc,3dAcn4fX2LyxO6uCnWFTx-2HKZ89uruurcKwLSCxbZ4,AQACAAE/Ultimate-Freenet-Index/55/";
	private static final String ALT_BASE_URI = BASE_URI_PROTOCOL+"://"+BASE_URI_CONTENT+'/'+BASE_KEY;
	
	static String tagname;
	LinkedHashMap<String, String> attributes;
	ParsedTag HTMLTag;
	TagVerifier verifier;
	HTMLFilter filter;
	HTMLFilter.HTMLParseContext pc;

	@Override
	public void setUp() throws Exception {
		filter = new HTMLFilter();
		attributes = new LinkedHashMap<String, String>();
		pc = filter.new HTMLParseContext(null, null, "utf-8", new GenericReadFilterCallback(new URI(ALT_BASE_URI), null, null, null), false);
	}

	@Override
	public void tearDown() {
		filter = null;
		attributes = null;
		pc = null;
		tagname = null;
		verifier = null;
		HTMLTag = null;
	}

	public void testHTMLTagWithInvalidNS() throws DataFilterException{
		tagname = "html";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		//Place an invalid namespace into the tag
		attributes.put("xmlns", "http://www.w3.org/1909/xhtml");
		//Place a unparsed attribute into the tag
		attributes.put("version", "-//W3C//DTD HTML 4.01 Transitional//EN");

		HTMLTag = new ParsedTag(tagname, attributes);
		final String HTML_INVALID_XMLNS = "<html version=\"-//W3C//DTD HTML 4.01 Transitional//EN\" />";

		assertEquals("HTML tag containing an invalid xmlns", HTML_INVALID_XMLNS, verifier.sanitize(HTMLTag, pc).toString());
	}

	public void testLinkTag() throws DataFilterException {
		tagname = "link";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("rel", "stylesheet");
		attributes.put("type", "text/css");
		attributes.put("target", "_blank");
		attributes.put("media", "print, handheld");
		attributes.put("href", "foo.css");

		HTMLTag = new ParsedTag(tagname, attributes);

		final String LINK_STYLESHEET = "<link rel=\"stylesheet\" type=\"text/css\" target=\"_blank\" media=\"print, handheld\" href=\"foo.css?type=text/css&amp;maybecharset=utf-8\" />";

		assertEquals("Link tag importing CSS", LINK_STYLESHEET, verifier.sanitize(HTMLTag, pc).toString());
	}

	public void testMetaTagHTMLContentType() throws DataFilterException {
		tagname = "meta";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("http-equiv","Content-type");
		attributes.put("content","text/html; charset=UTF-8");
		HTMLTag = new ParsedTag(tagname, attributes);

		assertEquals("Meta tag describing HTML content-type", HTMLTag.toString(), verifier.sanitize(HTMLTag, pc).toString());
	}

	public void testMetaTagXHTMLContentType() throws DataFilterException {
		tagname = "meta";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("http-equiv","Content-type");
		attributes.put("content","application/xhtml+xml; charset=UTF-8");
		HTMLTag = new ParsedTag(tagname, attributes);

		assertEquals("Meta tag describing XHTML content-type", HTMLTag.toString(), verifier.sanitize(HTMLTag, pc).toString());
	}

	public void testMetaTagUnknownContentType() throws DataFilterException {
		tagname = "meta";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("http-equiv","Content-type");
		attributes.put("content","want/fishsticks; charset=UTF-8");
		HTMLTag = new ParsedTag(tagname, attributes);

		try {
			verifier.sanitize(HTMLTag, pc);
			assertTrue("Meta tag describing an unknown content-type: should throw an error", false);
		} catch (DataFilterException e) {
			// Ok.
		}
	}

	public void testBodyTag() throws DataFilterException {
		tagname = "body";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("bgcolor", "pink");
		//Let's pretend the following is malicious JavaScript
		attributes.put("onload", "evil_scripting_magic");

		HTMLTag = new ParsedTag(tagname, attributes);

		final String BODY_TAG = "<body bgcolor=\"pink\" />";

		assertEquals("Body tag", BODY_TAG, verifier.sanitize(HTMLTag, pc).toString());
	}

	public void testFormTag() throws DataFilterException {
		tagname = "form";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("method", "POST");
		//Place a bad charset into the tag. This will get replaced with utf-8
		attributes.put("accept-charset", "iso-8859-1");
		attributes.put("action", "/library/");

		HTMLTag = new ParsedTag(tagname, attributes);
		final String FORM_TAG = "<form method=\"POST\" accept-charset=\"UTF-8\" action=\"/library/\" enctype=\"multipart/form-data\" />";

		assertEquals("Form tag", FORM_TAG, verifier.sanitize(HTMLTag, pc).toString());
	}

	public void testInvalidFormMethod() throws DataFilterException {
		tagname = "form";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("method", "INVALID_METHOD");
		attributes.put("action", "/library/");

		HTMLTag = new ParsedTag(tagname, attributes);

		assertNull("Form tag with an invalid method", verifier.sanitize(HTMLTag, pc));
	}

	public void testValidInputTag() throws DataFilterException {
		tagname = "input";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("type", "text");

		HTMLTag = new ParsedTag(tagname, attributes);

		assertEquals("Input tag with a valid type", HTMLTag.toString(), verifier.sanitize(HTMLTag, pc).toString());
	}

	public void testInvalidInputTag() throws DataFilterException {
		tagname = "input";
		verifier = HTMLFilter.allowedTagsVerifiers.get(tagname);

		attributes.put("type", "INVALID_TYPE");

		HTMLTag = new ParsedTag(tagname, attributes);

		assertNull("Input tag with an invalid type", verifier.sanitize(HTMLTag, pc));
	}
}
