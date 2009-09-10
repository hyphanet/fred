package freenet.clients.http.filter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Collection;
import java.util.Iterator;

import freenet.clients.http.filter.CSSTokenizerFilter.CSSPropertyVerifier;
import junit.framework.TestCase;

public class CSSParserTest extends TestCase {




	/** CSS1 Selectors */

	private final static HashMap<String,String> CSS1_SELECTOR= new HashMap<String,String>();
	static
	{
		CSS1_SELECTOR.put("h1 { }","h1");
		CSS1_SELECTOR.put("h1:link { }","h1:link");
		CSS1_SELECTOR.put("h1:visited { }","h1:visited");
		CSS1_SELECTOR.put("h1.warning { }","h1.warning");
		CSS1_SELECTOR.put("h1#myid { }","h1#myid");
		CSS1_SELECTOR.put("h1 h2 { }","h1 h2");
		CSS1_SELECTOR.put("h1:active { }","h1:active");
		CSS1_SELECTOR.put("h1:hover { }","h1:hover");
		CSS1_SELECTOR.put("h1:focus { }" ,"h1:focus");
		CSS1_SELECTOR.put("h1:first-line { }" ,"h1:first-line");
		CSS1_SELECTOR.put("h1:first-letter { }" ,"h1:first-letter");




	}

	/** CSS2 Selectors */
	private final static HashMap<String,String> CSS2_SELECTOR= new HashMap<String,String>();
	static
	{
		CSS2_SELECTOR.put("* { }","*");
		CSS2_SELECTOR.put("h1[foo] { }","h1[foo]");
		CSS2_SELECTOR.put("h1[foo=\"bar\"] { }", "h1[foo=\"bar\"]"); 
		CSS2_SELECTOR.put("h1[foo~=\"bar\"] { }", "h1[foo~=\"bar\"]");
		CSS2_SELECTOR.put("h1[foo~=\"bar\"] { }","h1[foo~=\"bar\"]");
		CSS2_SELECTOR.put("h1[foo|=\"en\"] { }","h1[foo|=\"en\"]");
		CSS2_SELECTOR.put("h1:first-child { }","h1:first-child");
		CSS2_SELECTOR.put("h1:lang(fr) { }","h1:lang(fr)");
		CSS2_SELECTOR.put("h1>h2 { }","h1>h2");
		CSS2_SELECTOR.put("h1+h2 { }", "h1+h2");

	}

	private static final String CSS_STRING_NEWLINES = "* { content: \"this string does not terminate\n}\nbody {\nbackground: url(http://www.google.co.uk/intl/en_uk/images/logo.gif); }\n\" }";
	private static final String CSS_STRING_NEWLINESC = " * {}\n body {";

	private static final String CSS_BACKGROUND_URL = "* { background: url(/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png); }";
	private static final String CSS_BACKGROUND_URLC = " * { background:url(/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png);}\n";
	
	private static final String CSS_LCASE_BACKGROUND_URL = "* { background: url(/ssk@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png); }";
	private static final String CSS_LCASE_BACKGROUND_URLC = " * { background:url(/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png);}\n";
	
	
	public void testCSS1Selector() throws IOException, URISyntaxException {


		Collection c = CSS1_SELECTOR.keySet();
		Iterator itr = c.iterator();
		while(itr.hasNext())
		{

			String key=itr.next().toString();
			String value=CSS1_SELECTOR.get(key);
			assertTrue(filter(key).contains(value));
		}


	}

	public void testCSS2Selector() throws IOException, URISyntaxException {
		Collection c = CSS2_SELECTOR.keySet();
		Iterator itr = c.iterator();
		int i=0; 
		while(itr.hasNext())
		{
			String key=itr.next().toString();
			String value=CSS2_SELECTOR.get(key);
			System.out.println("Test "+(i++)+" : "+key+" -> "+value);
			assertTrue("key="+key+" value="+filter(key), filter(key).contains(value));
		}

	}

	public void testNewlines() throws IOException, URISyntaxException {
		assertTrue("key="+CSS_STRING_NEWLINES+" value="+filter(CSS_STRING_NEWLINES), CSS_STRING_NEWLINESC.equals(filter(CSS_STRING_NEWLINES)));
	}
	
	public void testBackgroundURL() throws IOException, URISyntaxException {
		assertTrue("key="+CSS_BACKGROUND_URL+" value=\""+filter(CSS_BACKGROUND_URL)+"\"", CSS_BACKGROUND_URLC.equals(filter(CSS_BACKGROUND_URL)));
		
		// FIXME support lower case ssk@ in links from CSS
		//assertTrue("key="+CSS_LCASE_BACKGROUND_URL+" value=\""+filter(CSS_LCASE_BACKGROUND_URL)+"\"", CSS_LCASE_BACKGROUND_URLC.equals(filter(CSS_LCASE_BACKGROUND_URL)));
	}
	
	private String filter(String css) throws IOException, URISyntaxException {
		StringWriter w = new StringWriter();
		GenericReadFilterCallback cb = new GenericReadFilterCallback(new URI("/CHK@OR904t6ylZOwoobMJRmSn7HsPGefHSP7zAjoLyenSPw,x2EzszO4Kqot8akqmKYXJbkD-fSj6noOVGB-K2YisZ4,AAIC--8/1-works.html"), null);
		CSSParser p = new CSSParser(new StringReader(css), w, false, cb);
		p.parse();
		return w.toString();
	}
}
