/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Test;

import freenet.client.filter.ContentFilter.FilterStatus;
import freenet.clients.http.ExternalLinkToadlet;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.TestProperty;
import freenet.support.io.ArrayBucket;

/**
 * A simple meta-test to track regressions of the content-filter
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class ContentFilterTest {
    private static final String BASE_URI_PROTOCOL = "http";
    private static final String BASE_URI_CONTENT = "localhost:8888";
    private static final String BASE_KEY = "USK@0I8gctpUE32CM0iQhXaYpCMvtPPGfT4pjXm01oid5Zc,3dAcn4fX2LyxO6uCnWFTx-2HKZ89uruurcKwLSCxbZ4,AQACAAE/Ultimate-Freenet-Index/55/";
    private static final String BASE_URI = BASE_URI_PROTOCOL + "://" + BASE_URI_CONTENT + '/';
    private static final String ALT_BASE_URI = BASE_URI_PROTOCOL + "://" + BASE_URI_CONTENT + '/' + BASE_KEY;

    private static final String EXTERNAL_LINK = "www.evilwebsite.gov";
    private static final String EXTERNAL_LINK_OK = "<a />";
    // check that external links are not allowed
    private static final String EXTERNAL_LINK_CHECK1 = "<a href=\"" + EXTERNAL_LINK + "\"/>";
    private static final String EXTERNAL_LINK_CHECK2 = "<a href=\"" + BASE_URI_PROTOCOL + "://" + EXTERNAL_LINK + "\"/>";
    private static final String EXTERNAL_LINK_CHECK3 = "<a href=\"" + BASE_URI_CONTENT + "@http://" + EXTERNAL_LINK + "\"/>";

    private static final String INTERNAL_RELATIVE_LINK = "<a href=\"/KSK@gpl.txt\" />";
    private static final String INTERNAL_ABSOLUTE_LINK = "<a href=\"" + BASE_URI + "KSK@gpl.txt\" />";

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
    private static final String PREVENT_FPROXY_ACCESS = "<a href=\"" + BASE_URI + "\"/>";
    // @see bug #2921
    private static final String PREVENT_EXTERNAL_ACCESS_CSS_SIMPLE = "<style>div { background: url(" + BASE_URI + ") }</style>";
    private static final String PREVENT_EXTERNAL_ACCESS_CSS_CASE = "<style>div { background: uRl(" + BASE_URI + ") }</style>";
    private static final String PREVENT_EXTERNAL_ACCESS_CSS_ESCAPE = "<style>div { background: \\u\\r\\l(" + BASE_URI + ") }</style>";
    private static final String WHITELIST_STATIC_CONTENT = "<a href=\"/static/themes/clean/theme.css\" />";
    private static final String XHTML_VOIDELEMENT = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><br><hr></html>";
    private static final String XHTML_VOIDELEMENTC = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><br /><hr /></html>";
    private static final String XHTML_INCOMPLETEDOCUMENT = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body> <h1> helloworld <h2> helloworld";
    private static final String XHTML_INCOMPLETEDOCUMENTC = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body> <h1> helloworld <h2> helloworld</h2></h1></body></html>";
    private static final String XHTML_IMPROPERNESTING = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><b><i>helloworld</b></i></html>";
    private static final String XHTML_IMPROPERNESTINGC = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><b><i>helloworld</i></b></html>";

    private static final String CSS_STRING_NEWLINES = "<style>* { content: \"this string does not terminate\n}\nbody {\nbackground: url(http://www.google.co.uk/intl/en_uk/images/logo.gif); }\n\" }</style>";
    private static final String CSS_STRING_NEWLINESC = "<style>* {}\nbody { }\n</style>";

    private static final String HTML_STYLESHEET_MAYBECHARSET = "<link rel=\"stylesheet\" href=\"test.css\">";
    private static final String HTML_STYLESHEET_MAYBECHARSETC = "<link rel=\"stylesheet\" href=\"test.css?type=text/css&amp;maybecharset=iso-8859-1\" type=\"text/css\">";

    private static final String HTML_STYLESHEET_CHARSET = "<link rel=\"stylesheet\" charset=\"utf-8\" href=\"test.css\">";
    private static final String HTML_STYLESHEET_CHARSETC = "<link rel=\"stylesheet\" charset=\"utf-8\" href=\"test.css?type=text/css%3b%20charset=utf-8\" type=\"text/css\">";

    private static final String HTML_STYLESHEET_CHARSET_BAD = "<link rel=\"stylesheet\" charset=\"utf-8&max-size=4194304\" href=\"test.css\">";
    private static final String HTML_STYLESHEET_CHARSET_BADC = "<link rel=\"stylesheet\" href=\"test.css?type=text/css&amp;maybecharset=iso-8859-1\" type=\"text/css\">";

    private static final String HTML_STYLESHEET_CHARSET_BAD1 = "<link rel=\"stylesheet\" type=\"text/css; charset=utf-8&max-size=4194304\" href=\"test.css\">";
    private static final String HTML_STYLESHEET_CHARSET_BAD1C = "<link rel=\"stylesheet\" type=\"text/css\" href=\"test.css?type=text/css&amp;maybecharset=iso-8859-1\">";

    private static final String HTML_STYLESHEET_WITH_MEDIA = "<LINK REL=\"stylesheet\" TYPE=\"text/css\"\nMEDIA=\"print, handheld\" HREF=\"foo.css\">";
    private static final String HTML_STYLESHEET_WITH_MEDIAC = "<LINK rel=\"stylesheet\" type=\"text/css\" media=\"print, handheld\" href=\"foo.css?type=text/css&amp;maybecharset=iso-8859-1\">";

    private static final String FRAME_SRC_CHARSET = "<frame src=\"test.html?type=text/html; charset=UTF-8\">";
    private static final String FRAME_SRC_CHARSETC = "<frame src=\"test.html?type=text/html%3b%20charset=UTF-8\">";

    private static final String FRAME_SRC_CHARSET_BAD = "<frame src=\"test.html?type=text/html; charset=UTF-8&max-size=4194304\">";
    private static final String FRAME_SRC_CHARSET_BADC = "<frame src=\"test.html?type=text/html%3b%20charset=UTF-8\">";

    private static final String FRAME_SRC_CHARSET_BAD1 = "<frame src=\"test.html?type=text/html; charset=UTF-8%26max-size=4194304\">";
    private static final String FRAME_SRC_CHARSET_BAD1C = "<frame src=\"test.html?type=text/html\">";

    private static final String SPAN_WITH_STYLE = "<span style=\"font-family: verdana, sans-serif; color: red;\">";

	private static final String HTML5_TAGS = "<main><article><details><summary><mark>TLDR</mark></summary><center>Too Long Di<wbr />dn&rsquo;t Read</center></details><section><figure><figcaption>Fig.1</figcaption></figure></article></main>";
	private static final String HTML5_BDI_RUBY = "<small dir=\"auto\"><bdi>&#x0627;&#x06CC;&#x0631;&#x0627;&#x0646;</bdi>, <bdo><ruby>&#xBD81;<rt>North</rt>&#xD55C;<rt>Korea</rt></ruby><rp>North Korea</rp></ruby></bdo></small>";

	private static final String BASE_HREF = "<base href=\"/"+BASE_KEY+"\">";
	private static final String BAD_BASE_HREF = "<base href=\"/\">";
	private static final String BAD_BASE_HREF2 = "<base href=\"//www.google.com\">";
	private static final String BAD_BASE_HREF3 = "<base>";
	private static final String BAD_BASE_HREF4 = "<base id=\"blah\">";
	private static final String BAD_BASE_HREF5 = "<base href=\"http://www.google.com/\">";
	private static final String DELETED_BASE_HREF = "<!-- deleted invalid base href -->";

    // From CSS spec

	private static final String CSS_SPEC_EXAMPLE1 = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\">\n<HTML>\n  <HEAD>\n  <TITLE>Bach's home page</TITLE>\n  <STYLE type=\"text/css\">\n    body {\n      font-family: \"Gill Sans\", sans-serif;\n      font-size: 12pt;\n      margin: 3em;\n\n    }\n  </STYLE>\n  </HEAD>\n  <BODY>\n    <H1>Bach's home page</H1>\n    <P>Johann Sebastian Bach was a prolific composer.\n  </BODY>\n</HTML>";
	private static final String HTML_START_TO_BODY = "<html><head></head><body>";
	private static final String HTML_BODY_END = "</body></html>";
	private static final String HTML_VIDEO_TAG = "<video></video>";
	private static final String HTML_AUDIO_TAG = "<audio></audio>";
	private static final List<String> HTML_MEDIA_TAG_COMBINATIONS = Arrays.asList(
			HTML_VIDEO_TAG,
			HTML_AUDIO_TAG,
			HTML_VIDEO_TAG + HTML_AUDIO_TAG,
			HTML_AUDIO_TAG + HTML_AUDIO_TAG);

	private static void testOneHTMLFilter(String html) throws Exception {
		assertEquals(html, htmlFilter(html));
	}
	
    @Test
    public void testHTMLFilter() throws Exception {
        if (TestProperty.VERBOSE) {
            Logger.setupStdoutLogging(LogLevel.MINOR, "freenet.client.filter.Generic:DEBUG");
        }

        // General sanity checks
        // is "relativization" working?
        testOneHTMLFilter(INTERNAL_RELATIVE_LINK);
        assertEquals(INTERNAL_RELATIVE_LINK, htmlFilter(INTERNAL_RELATIVE_LINK, true));
        assertEquals(INTERNAL_RELATIVE_LINK1, htmlFilter(INTERNAL_RELATIVE_LINK1, true));
        assertEquals(INTERNAL_RELATIVE_LINK, htmlFilter(INTERNAL_ABSOLUTE_LINK));
        // are external links stripped out ?
        assertTrue(htmlFilter(EXTERNAL_LINK_CHECK1).startsWith(EXTERNAL_LINK_OK));
        assertTrue(htmlFilter(EXTERNAL_LINK_CHECK2).contains(ExternalLinkToadlet.PATH));
        assertTrue(htmlFilter(EXTERNAL_LINK_CHECK3).startsWith(EXTERNAL_LINK_OK));

        // regression testing
        // bug #710
        testOneHTMLFilter(ANCHOR_TEST);
        testOneHTMLFilter(ANCHOR_TEST_EMPTY);
        testOneHTMLFilter(ANCHOR_TEST_SPECIAL);
        assertEquals(ANCHOR_TEST_SPECIAL2_RESULT, htmlFilter(ANCHOR_TEST_SPECIAL2));
        // bug #2496
        testOneHTMLFilter(ANCHOR_RELATIVE1);
        testOneHTMLFilter(ANCHOR_RELATIVE2);
        testOneHTMLFilter(ANCHOR_FALSE_POS1);
        testOneHTMLFilter(ANCHOR_FALSE_POS2);
        // EVIL HACK TEST for #2496 + #2451
        assertEquals(ANCHOR_MIXED_RESULT, htmlFilter(ANCHOR_MIXED));
        // bug #2451
        assertEquals(POUNT_CHARACTER_ENCODING_TEST_RESULT, htmlFilter(POUNT_CHARACTER_ENCODING_TEST));
        // bug #2297
        assertTrue(htmlFilter(PREVENT_FPROXY_ACCESS).contains(ExternalLinkToadlet.PATH));
        // bug #2921
        assertTrue(htmlFilter(PREVENT_EXTERNAL_ACCESS_CSS_SIMPLE).contains("div { }"));
        assertTrue(htmlFilter(PREVENT_EXTERNAL_ACCESS_CSS_ESCAPE).contains("div { }"));
        assertTrue(htmlFilter(PREVENT_EXTERNAL_ACCESS_CSS_CASE).contains("div { }"));
        testOneHTMLFilter(WHITELIST_STATIC_CONTENT);
        assertEquals(XHTML_VOIDELEMENTC, htmlFilter(XHTML_VOIDELEMENT));
        assertEquals(XHTML_INCOMPLETEDOCUMENTC, htmlFilter(XHTML_INCOMPLETEDOCUMENT));
        assertEquals(XHTML_IMPROPERNESTINGC, htmlFilter(XHTML_IMPROPERNESTING));

        assertEquals(CSS_STRING_NEWLINESC, htmlFilter(CSS_STRING_NEWLINES));

        assertEquals(HTML_STYLESHEET_MAYBECHARSETC, htmlFilter(HTML_STYLESHEET_MAYBECHARSET, true));
        assertEquals(HTML_STYLESHEET_CHARSETC, htmlFilter(HTML_STYLESHEET_CHARSET, true));
        assertEquals(HTML_STYLESHEET_CHARSET_BADC, htmlFilter(HTML_STYLESHEET_CHARSET_BAD, true));
        assertEquals(HTML_STYLESHEET_CHARSET_BAD1C, htmlFilter(HTML_STYLESHEET_CHARSET_BAD1, true));
        assertEquals(HTML_STYLESHEET_WITH_MEDIAC, htmlFilter(HTML_STYLESHEET_WITH_MEDIA, true));

        assertEquals(FRAME_SRC_CHARSETC, htmlFilter(FRAME_SRC_CHARSET, true));
        assertEquals(FRAME_SRC_CHARSET_BADC, htmlFilter(FRAME_SRC_CHARSET_BAD, true));
        assertEquals(FRAME_SRC_CHARSET_BAD1C, htmlFilter(FRAME_SRC_CHARSET_BAD1, true));

        testOneHTMLFilter(CSS_SPEC_EXAMPLE1);

        testOneHTMLFilter(SPAN_WITH_STYLE);
        testOneHTMLFilter(HTML5_TAGS);
        testOneHTMLFilter(HTML5_BDI_RUBY);

        testOneHTMLFilter(BASE_HREF);
        assertEquals(DELETED_BASE_HREF, htmlFilter(BAD_BASE_HREF));
        assertEquals(DELETED_BASE_HREF, htmlFilter(BAD_BASE_HREF2));
        assertEquals(DELETED_BASE_HREF, htmlFilter(BAD_BASE_HREF3));
        assertEquals(DELETED_BASE_HREF, htmlFilter(BAD_BASE_HREF4));
        assertEquals(DELETED_BASE_HREF, htmlFilter(BAD_BASE_HREF5));

    }

    @Test
    public void testM3UPlayerAddition() throws Exception {
		// m3u filter is added when there is a video or audio tag
		for (String content : HTML_MEDIA_TAG_COMBINATIONS) {
			String expected = HTML_START_TO_BODY
					+ content
					+ HTMLFilter.m3uPlayerScriptTagContent()
					+ HTML_BODY_END;
			String unparsed = HTML_START_TO_BODY
					+ content
					+ HTML_BODY_END;
			// m3u filter is added
			assertEquals(expected, htmlFilter(unparsed));
			// ensure that that’s a script tag
			String expectedStart = HTML_START_TO_BODY + content + "<script";
			MatcherAssert.assertThat(htmlFilter(unparsed), Matchers.startsWith(expectedStart));
		}
	}

    private static final String META_TIME_ONLY = "<meta http-equiv=\"refresh\" content=\"5\">";
    private static final String META_TIME_ONLY_WRONG_CASE = "<meta http-equiv=\"RefResH\" content=\"5\">";
    private static final String META_TIME_ONLY_TOO_SHORT = "<meta http-equiv=\"refresh\" content=\"0\">";
    private static final String META_TIME_ONLY_NEGATIVE = "<meta http-equiv=\"refresh\" content=\"-5\">";

    private static final String META_TIME_ONLY_BADNUM1 = "<meta http-equiv=\"refresh\" content=\"5.5\">";
    private static final String META_TIME_ONLY_BADNUM2 = "<meta http-equiv=\"refresh\" content=\"\">";
    private static final String META_TIME_ONLY_BADNUM_OUT = "<!-- doesn't parse as number in meta refresh -->";

    private static final String META_CHARSET = "<html><head><meta charset=\"UTF-8\" />";
    private static final String META_CHARSET_LOWER = "<!DOCTYPE html>\n"
        + "<html lang=\"de\">\n"
        + "<head>\n"
        + "<!-- 2022-12-08 Do 01:20 -->\n"
        + "<meta charset=\"utf-8\" />\n"
        + "<title>Some Title</title>";
    private static final String META_CHARSET_LOWER_RES = "<!DOCTYPE html>\n"
        + "<html lang=\"de\">\n"
        + "<head>\n"
        + "<!--  2022-12-08 Do 01:20  -->\n" // comment has additional spaces after content filter
        + "<meta charset=\"utf-8\" />\n"
        + "<title>Some Title</title>";

    private static final String META_VALID_REDIRECT = "<meta http-equiv=\"refresh\" content=\"30; url=/KSK@gpl.txt\">";
    private static final String META_VALID_REDIRECT_NOSPACE = "<meta http-equiv=\"refresh\" content=\"30;url=/KSK@gpl.txt\">";

    private static final String META_BOGUS_REDIRECT1 = "<meta http-equiv=\"refresh\" content=\"30; url=/\">";
    private static final String META_BOGUS_REDIRECT2 = "<meta http-equiv=\"refresh\" content=\"30; url=/plugins\">";
    private static final String META_BOGUS_REDIRECT3 = "<meta http-equiv=\"refresh\" content=\"30; url=http://www.google.com\">";
    private static final String META_BOGUS_REDIRECT4 = "<meta http-equiv=\"refresh\" content=\"30; url=//www.google.com\">";
    private static final String META_BOGUS_REDIRECT5 = "<meta http-equiv=\"refresh\" content=\"30; url=\"/KSK@gpl.txt\"\">";
    private static final String META_BOGUS_REDIRECT6 = "<meta http-equiv=\"refresh\" content=\"30; /KSK@gpl.txt\">";
    private static final String META_BOGUS_REDIRECT1_OUT = "<!-- GenericReadFilterCallback.malformedRelativeURL-->";
    private static final String META_BOGUS_REDIRECT3_OUT = "<meta http-equiv=\"refresh\" content=\"30; url=/external-link/?_CHECKED_HTTP_=http://www.google.com\">";
    private static final String META_BOGUS_REDIRECT4_OUT = "<!-- GenericReadFilterCallback.deletedURI-->";
    private static final String META_BOGUS_REDIRECT_NO_URL = "<!-- no url but doesn't parse as number in meta refresh -->";

    @Test
    public void testMetaRefresh() throws Exception {
        HTMLFilter.metaRefreshSamePageMinInterval = 5;
        HTMLFilter.metaRefreshRedirectMinInterval = 30;
        assertEquals(META_TIME_ONLY, headFilter(META_TIME_ONLY));
        assertEquals(META_TIME_ONLY, headFilter(META_TIME_ONLY_WRONG_CASE));
        assertEquals(META_TIME_ONLY, headFilter(META_TIME_ONLY_TOO_SHORT));
        assertEquals("", headFilter(META_TIME_ONLY_NEGATIVE));
        assertEquals(META_TIME_ONLY_BADNUM_OUT, headFilter(META_TIME_ONLY_BADNUM1));
        assertEquals(META_TIME_ONLY_BADNUM_OUT, headFilter(META_TIME_ONLY_BADNUM2));
        assertEquals(META_VALID_REDIRECT, headFilter(META_VALID_REDIRECT));
        assertEquals(META_VALID_REDIRECT, headFilter(META_VALID_REDIRECT_NOSPACE));
        assertEquals(META_BOGUS_REDIRECT1_OUT, headFilter(META_BOGUS_REDIRECT1));
        assertEquals(META_BOGUS_REDIRECT1_OUT, headFilter(META_BOGUS_REDIRECT2));
        assertEquals(META_BOGUS_REDIRECT3_OUT, headFilter(META_BOGUS_REDIRECT3));
        assertEquals(META_BOGUS_REDIRECT4_OUT, headFilter(META_BOGUS_REDIRECT4));
        assertEquals(META_BOGUS_REDIRECT1_OUT, headFilter(META_BOGUS_REDIRECT5));
        assertEquals(META_BOGUS_REDIRECT_NO_URL, headFilter(META_BOGUS_REDIRECT6));
        HTMLFilter.metaRefreshSamePageMinInterval = -1;
        HTMLFilter.metaRefreshRedirectMinInterval = -1;
        assertEquals("", headFilter(META_TIME_ONLY));
        assertEquals("", headFilter(META_TIME_ONLY_WRONG_CASE));
        assertEquals("", headFilter(META_TIME_ONLY_TOO_SHORT));
        assertEquals("", headFilter(META_TIME_ONLY_NEGATIVE));
        assertEquals("", headFilter(META_TIME_ONLY_BADNUM1));
        assertEquals("", headFilter(META_TIME_ONLY_BADNUM2));
        assertEquals("", headFilter(META_VALID_REDIRECT));
        assertEquals("", headFilter(META_VALID_REDIRECT_NOSPACE));
        assertEquals("", headFilter(META_BOGUS_REDIRECT1));
        assertEquals("", headFilter(META_BOGUS_REDIRECT2));
        assertEquals("", headFilter(META_BOGUS_REDIRECT3));
        assertEquals("", headFilter(META_BOGUS_REDIRECT4));
        assertEquals("", headFilter(META_BOGUS_REDIRECT5));
        assertEquals("", headFilter(META_BOGUS_REDIRECT6));
    }

    private String headFilter(String data) throws Exception {
        String s = htmlFilter("<head>" + data + "</head>");
        if (s == null) {
            return s;
        }

		MatcherAssert.assertThat(s, startsWith("<head>"));
		MatcherAssert.assertThat(s, endsWith("</head>"));

        s = s.substring("<head>".length());
        s = s.substring(0, s.length() - "</head>".length());
        return s;
    }

    @Test
    public void testThatHtml5MetaCharsetIsPreserved() throws Exception {
        assertEquals(META_CHARSET, htmlFilter(META_CHARSET));
        assertEquals(META_CHARSET_LOWER_RES, htmlFilter(META_CHARSET_LOWER));
    }

    @Test
    public void testEvilCharset() throws IOException {
        // This is why we need to disallow characters before <html> !!
        String s = "<html><body><a href=\"http://www.google.com/\">Blah</a>";
        String end = "</body></html>";
        String alt = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-16\"></head><body><a href=\"http://www.freenetproject.org/\">Blah</a></body></html>";
        if ((s.length() + end.length()) % 2 == 1) {
            s += " ";
        }
        s = s + end;
        byte[] buf = s.getBytes(StandardCharsets.UTF_8);
        byte[] utf16bom = new byte[]{(byte) 0xFE, (byte) 0xFF};
        byte[] bufUTF16 = alt.getBytes(StandardCharsets.UTF_16);
        byte[] total = new byte[buf.length + utf16bom.length + bufUTF16.length];
        System.arraycopy(utf16bom, 0, total, 0, utf16bom.length);
        System.arraycopy(buf, 0, total, utf16bom.length, buf.length);
        System.arraycopy(bufUTF16, 0, total, utf16bom.length + buf.length, bufUTF16.length);
        HTMLFilter filter = new HTMLFilter();
        boolean failed = false;
        List<RuntimeException> failures = new ArrayList<>();
        try (
            FileOutputStream fos = new FileOutputStream("output.utf16")
        ){
            ArrayBucket out = new ArrayBucket();
            filter.readFilter(new ArrayBucket(total).getInputStream(), out.getOutputStream(), "UTF-16", null, null, null);
            fos.write(out.toByteArray());
            failed = true;
            failures.add(new RuntimeException("Filter accepted dangerous UTF8 text with BOM as UTF16! (HTMLFilter)"));
        } catch (DataFilterException e) {
            System.out.println("Failure: " + e);
            e.printStackTrace();
            if (e.getCause() != null) {
                e.getCause().printStackTrace();
            }
            // Ok.
        }
        try (
            FileOutputStream fos = new FileOutputStream("output.filtered")
        ){
            ArrayBucket out = new ArrayBucket();
            FilterStatus fo = ContentFilter.filter(new ArrayBucket(total).getInputStream(), out.getOutputStream(), "text/html", null, null, null);
            fos.write(out.toByteArray());
            failed = true;
            failures.add(new RuntimeException("Filter accepted dangerous UTF8 text with BOM as UTF16! (ContentFilter) - Detected charset: " + fo.charset));
        } catch (DataFilterException e) {
            System.out.println("Failure: " + e);
            e.printStackTrace();
            if (e.getCause() != null) {
                e.getCause().printStackTrace();
            }
            // Ok.
        }

        if (failed) {
            try (FileOutputStream fos = new FileOutputStream("unfiltered")) {
                fos.write(total);
            }
            throw failures.get(0);
        }
    }

    public static String htmlFilter(String data) throws Exception {
        if (data.startsWith("<html")) return htmlFilter(data, false);
        if (data.startsWith("<?")) return htmlFilter(data, false);
        String s = htmlFilter("<html>" + data + "</html>", false);
        assertTrue(s.startsWith("<html>"));
        s = s.substring("<html>".length());
        assertTrue("s = \"" + s + "\"", s.endsWith("</html>"));
        s = s.substring(0, s.length() - "</html>".length());
        return s;
    }

    public static String htmlFilter(String data, boolean alt) throws Exception {
        String typeName = "text/html";
        URI baseURI = new URI(alt ? ALT_BASE_URI : BASE_URI);
        byte[] dataToFilter = data.getBytes(StandardCharsets.UTF_8);
        ArrayBucket input = new ArrayBucket(dataToFilter);
        ArrayBucket output = new ArrayBucket();
        try (
            OutputStream outputStream = output.getOutputStream();
            InputStream inputStream = input.getInputStream()
        ) {
            ContentFilter.filter(inputStream, outputStream, typeName, baseURI, null, null, null, null);
        } finally {
            input.free();
        }
        String returnValue = output.toString();
        output.free();
        return returnValue;
    }

    @Test
    public void testLowerCaseExtensions() {
        for (FilterMIMEType type : ContentFilter.mimeTypesByName.values()) {
            String ext = type.primaryExtension;
            if (ext != null) {
                assertEquals(ext, ext.toLowerCase());
            }
            String[] exts = type.alternateExtensions;
            if (ext != null) {
                for (String s : exts) {
                    assertEquals(s, s.toLowerCase());
                }
            }
        }
    }
}
