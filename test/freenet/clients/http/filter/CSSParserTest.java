package freenet.clients.http.filter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import junit.framework.TestCase;

public class CSSParserTest extends TestCase {

	/** CSS1 Selectors */
	private final static String[] CSS1_SELECTOR = new String[] { 
	        "E { }", //
	        "E:link { }", //
	        "E:visited { }", //
	        "E::first-line { }", //
	        "E::first-letter { }", //
	        "E.warning { }", //
	        "E#myid { }", //
	        "E F { }", //
	        "E:active { }", //
	        "E:hover { }", //
	        "E:focus { }" };
	/** CSS2 Selectors */
	private final static String[] CSS2_SELECTOR = new String[] {
	        "* { }", //
	        // "E[foo] { }", //
	        // "E[foo=\"bar\"] { }", //
	        // "E[foo~=\"bar\"] { }", //
	        // "E[hfoo|=\"en\"] { }", //
	        "E:first-child { }", //
	        // "E:lang(fr) { }", //
	        "E::before { }", //
	        "E::after { }", //
	        "E > F { }", //
	        "E + F { }" };
	/** CSS3 Selectors */
	private final static String[] CSS3_SELECTOR = new String[] { 
		    // "E[foo^=\"bar\"] { }", //
		    // "E[foo$=\"bar\"] { }", //
	        // "E[foo*=\"bar\"] { }", //
	        "E:root { }", //
	        // "E:nth-child(n) { }", //
	        // "E:nth-last-child(n) { }", //
	        // "E:nth-of-type(n) { }", //
	        // "E:nth-last-of-type(n) { }", //
	        "E:last-child { }", //
	        "E:first-of-type { }", //
	        "E:last-of-type { }", //
	        "E:only-child { }", //
	        "E:only-of-type { }", //
	        "E:empty { }", //
	        "E:target { }", //
	        "E:enabled { }", //
	        "E:disabled { }", //
	        "E:checked { }", //
	        // "E:not(s) { }", //
	// "E ~ F { }" 
	};

	public void testCSS1Selector() throws IOException {
		for (String css : CSS1_SELECTOR)
			assertEquals("CSS1_SELECTOR", css, filter(css));
	}

	public void testCSS2Selector() throws IOException {
		for (String css : CSS2_SELECTOR)
			assertEquals("CSS2_SELECTOR", css, filter(css));
	}

	public void testCSS3Selector() throws IOException {
		for (String css : CSS3_SELECTOR)
			assertEquals("CSS3_SELECTOR", css, filter(css));
	}

	private String filter(String css) throws IOException {
		System.err.println( css );
		System.err.println( );
		StringWriter w = new StringWriter();
		CSSParser p = new CSSParser(new StringReader(css), w, false, null);
		p.parse();
		return w.toString();
	}
}
