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
import freenet.l10n.NodeL10n;
import freenet.support.Logger;
import freenet.support.LoggerHook.InvalidThresholdException;
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
	private static final String CSS_STRING_NEWLINESC = " * {}\n body {}\n";

	private static final String CSS_BACKGROUND_URL = "* { background: url(/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png); }";
	private static final String CSS_BACKGROUND_URLC = " * { background:url(/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png);}\n";
	
	private static final String CSS_LCASE_BACKGROUND_URL = "* { background: url(/ssk@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png); }";
	private static final String CSS_LCASE_BACKGROUND_URLC = " * { background:url(/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png);}\n";
	
	// not adding ?type=text/css is exploitable, so check for it.
	private static final String CSS_IMPORT = "@import url(\"/KSK@test\");";
	private static final String CSS_IMPORTC = "@import url(\"/KSK@test?type=text/css\");";

	private static final String CSS_IMPORT2 = "@import url(\"/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html\") screen;";
	private static final String CSS_IMPORT2C = "@import url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html?type=text/css\") screen;";

	private static final String CSS_ESCAPED_LINK = "* { background: url(\00002f\00002fwww.google.co.uk/intl/en_uk/images/logo.gif); }";
	private static final String CSS_ESCAPED_LINKC = " * {}\n";
	
	private static final String CSS_ESCAPED_LINK2 = "* { background: url(\\/\\/www.google.co.uk/intl/en_uk/images/logo.gif); }";
	private static final String CSS_ESCAPED_LINK2C = " * {}\n";
	
	// CSS2.1 spec, 4.1.7
	private static final String CSS_DELETE_INVALID_SELECTOR = "h1, h2 {color: green }\nh3, h4 & h5 {color: red }\nh6 {color: black }\n";
	private static final String CSS_DELETE_INVALID_SELECTORC = "h1, h2 { color:green;}\n h6 { color:black;}\n";
	
	public void setUp() throws InvalidThresholdException {
		new NodeL10n();
    	//Logger.setupStdoutLogging(Logger.MINOR, "freenet.clients.http.filter:DEBUG");
	}
	
	public void testCSS1Selector() throws IOException, URISyntaxException {


		Collection c = CSS1_SELECTOR.keySet();
		Iterator itr = c.iterator();
		while(itr.hasNext())
		{

			String key=itr.next().toString();
			String value=CSS1_SELECTOR.get(key);
			assertTrue(filter(key).contains(value));
		}

		assertTrue("key=\""+CSS_DELETE_INVALID_SELECTOR+"\" value=\""+filter(CSS_DELETE_INVALID_SELECTOR)+"\" should be \""+CSS_DELETE_INVALID_SELECTORC+"\"", CSS_DELETE_INVALID_SELECTORC.equals(filter(CSS_DELETE_INVALID_SELECTOR)));
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
		assertTrue("key=\""+CSS_STRING_NEWLINES+"\" value=\""+filter(CSS_STRING_NEWLINES)+"\" should be: \""+CSS_STRING_NEWLINESC+"\"", CSS_STRING_NEWLINESC.equals(filter(CSS_STRING_NEWLINES)));
	}
	
	public void testBackgroundURL() throws IOException, URISyntaxException {
		assertTrue("key="+CSS_BACKGROUND_URL+" value=\""+filter(CSS_BACKGROUND_URL)+"\"", CSS_BACKGROUND_URLC.equals(filter(CSS_BACKGROUND_URL)));
		
		// FIXME support lower case ssk@ in links from CSS
		//assertTrue("key="+CSS_LCASE_BACKGROUND_URL+" value=\""+filter(CSS_LCASE_BACKGROUND_URL)+"\"", CSS_LCASE_BACKGROUND_URLC.equals(filter(CSS_LCASE_BACKGROUND_URL)));
	}
	
	public void testImports() throws IOException, URISyntaxException {
		assertTrue("key="+CSS_IMPORT+" value=\""+filter(CSS_IMPORT)+"\"", CSS_IMPORTC.equals(filter(CSS_IMPORT)));
		assertTrue("key="+CSS_IMPORT2+" value=\""+filter(CSS_IMPORT2)+"\"", CSS_IMPORT2C.equals(filter(CSS_IMPORT2)));
	}
	
	public void testEscape() throws IOException, URISyntaxException {
		assertTrue("key="+CSS_ESCAPED_LINK+" value=\""+filter(CSS_ESCAPED_LINK)+"\"", CSS_ESCAPED_LINKC.equals(filter(CSS_ESCAPED_LINK)));
		assertTrue("key="+CSS_ESCAPED_LINK2+" value=\""+filter(CSS_ESCAPED_LINK2)+"\"", CSS_ESCAPED_LINK2C.equals(filter(CSS_ESCAPED_LINK2)));
	}
	
	private String filter(String css) throws IOException, URISyntaxException {
		StringWriter w = new StringWriter();
		GenericReadFilterCallback cb = new GenericReadFilterCallback(new URI("/CHK@OR904t6ylZOwoobMJRmSn7HsPGefHSP7zAjoLyenSPw,x2EzszO4Kqot8akqmKYXJbkD-fSj6noOVGB-K2YisZ4,AAIC--8/1-works.html"), null);
		CSSParser p = new CSSParser(new StringReader(css), w, false, cb);
		p.parse();
		return w.toString();
	}
}
