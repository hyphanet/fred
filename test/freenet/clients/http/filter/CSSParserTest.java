package freenet.clients.http.filter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import junit.framework.TestCase;
import freenet.clients.http.filter.CharsetExtractor.BOMDetection;
import freenet.clients.http.filter.ContentFilter.FilterOutput;
import freenet.l10n.NodeL10n;
import freenet.support.Logger;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;

public class CSSParserTest extends TestCase {




	// FIXME should specify exact output values
	/** CSS1 Selectors */
	private final static HashMap<String,String> CSS1_SELECTOR= new HashMap<String,String>();
	static
	{
		CSS1_SELECTOR.put("h1 {}","h1");
		CSS1_SELECTOR.put("h1:link {}","h1:link");
		CSS1_SELECTOR.put("h1:visited {}","h1:visited");
		CSS1_SELECTOR.put("h1.warning {}","h1.warning");
		CSS1_SELECTOR.put("h1#myid {}","h1#myid");
		CSS1_SELECTOR.put("h1 h2 {}","h1 h2");
		CSS1_SELECTOR.put("h1:active {}","h1:active");
		CSS1_SELECTOR.put("h1:hover {}","h1:hover");
		CSS1_SELECTOR.put("h1:focus {}" ,"h1:focus");
		CSS1_SELECTOR.put("h1:first-line {}" ,"h1:first-line");
		CSS1_SELECTOR.put("h1:first-letter {}" ,"h1:first-letter");




	}

	// FIXME should specify exact output values
	/** CSS2 Selectors */
	private final static HashMap<String,String> CSS2_SELECTOR= new HashMap<String,String>();
	static
	{
		CSS2_SELECTOR.put("* {}","*");
		CSS2_SELECTOR.put("h1[foo] {}","h1[foo]");
		CSS2_SELECTOR.put("h1[foo=\"bar\"] {}", "h1[foo=\"bar\"]"); 
		CSS2_SELECTOR.put("h1[foo~=\"bar\"] {}", "h1[foo~=\"bar\"]");
		CSS2_SELECTOR.put("h1[foo|=\"en\"] {}","h1[foo|=\"en\"]");
		CSS2_SELECTOR.put("h1:first-child {}","h1:first-child");
		CSS2_SELECTOR.put("h1:lang(fr) {}","h1:lang(fr)");
		CSS2_SELECTOR.put("h1>h2 {}","h1>h2");
		CSS2_SELECTOR.put("h1+h2 {}", "h1+h2");
		CSS2_SELECTOR.put("div.foo {}", "div.foo");
		CSS2_SELECTOR.put("p.marine.pastoral { color: green }", "p.marine.pastoral");
		
		// Spaces in a selector string
		CSS2_SELECTOR.put("h1[foo=\"bar bar\"] {}", "h1[foo=\"bar bar\"]");
		CSS2_SELECTOR.put("h1[foo=\"bar+bar\"] {}", "h1[foo=\"bar+bar\"]");
		CSS2_SELECTOR.put("h1[foo=\"bar\\\" bar\"] {}", "h1[foo=\"bar\\\" bar\"]");
		// Wierd one from the CSS spec
		CSS2_SELECTOR.put("p[example=\"public class foo\\\n{\\\n    private int x;\\\n\\\n    foo(int x) {\\\n        this.x = x;\\\n    }\\\n\\\n}\"] { color: red }", 
				"p[example=\"public class foo{    private int x;    foo(int x) {        this.x = x;    }}\"] { color: red;}");
		// Escaped anything inside an attribute selector. This is allowed.
		CSS2_SELECTOR.put("h1[foo=\"hello\\202 \"] {}", "h1[foo=\"hello\\202 \"] {}");
		// Escaped quotes inside a string inside an attribute selector. This is allowed.
		CSS2_SELECTOR.put("h1[foo=\"\\\"test\\\"\"] {}", "h1[foo=\"\\\"test\\\"\"] {}");
		CSS2_SELECTOR.put("a:focus:hover { background: white;}", "a:focus:hover { background: white;}");
	}
	
	private final static HashSet<String> CSS2_BAD_SELECTOR= new HashSet<String>();
	static
	{
		// Doubled =
		CSS2_BAD_SELECTOR.add("h1[foo=bar=bat] {}");
		CSS2_BAD_SELECTOR.add("h1[foo~=bar~=bat] {}");
		CSS2_BAD_SELECTOR.add("h1[foo|=bar|=bat] {}");
		// Escaping ]
		CSS2_BAD_SELECTOR.add("h1[foo=bar\\] {}");
		CSS2_BAD_SELECTOR.add("h1[foo=\"bar\\] {}");
		// Unclosed string
		CSS2_BAD_SELECTOR.add("h1[foo=\"bar] {}");
		
		CSS2_BAD_SELECTOR.add("h1:langblahblah(fr) {}");
		
		// THE FOLLOWING ARE VALID BUT DISALLOWED
		// ] inside string inside attribute selector: way too confusing for parsers.
		// FIXME one day we should escape the ] to make this both valid and easy to parse, rather than dropping it.
		CSS2_BAD_SELECTOR.add("h1[foo=\"bar]\"] {}");
		CSS2_BAD_SELECTOR.add("h1[foo=bar\\]] {}");
		// Closing an escape with \r\n. This is supported by verifying and splitting logic, but not by the tokeniser.
		// FIXME fix this.
		CSS2_BAD_SELECTOR.add("h1[foo=\"hello\\202\r\n\"] {}");
	}

	private static final String CSS_STRING_NEWLINES = "* { content: \"this string does not terminate\n}\nbody {\nbackground: url(http://www.google.co.uk/intl/en_uk/images/logo.gif); }\n\" }";
	private static final String CSS_STRING_NEWLINESC = "* {}\nbody { }\n";

	private static final String CSS_BACKGROUND_URL = "* { background: url(/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png); }";
	private static final String CSS_BACKGROUND_URLC = "* { background: url(\"/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png\"); }";
	
	private static final String CSS_LCASE_BACKGROUND_URL = "* { background: url(/ssk@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png); }\n";
	private static final String CSS_LCASE_BACKGROUND_URLC = "* { background: url(\"/SSK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/activelink-index-text-76/activelink.png\"); }\n";
	
	// not adding ?type=text/css is exploitable, so check for it.
	private static final String CSS_IMPORT = "@import url(\"/KSK@test\");";
	private static final String CSS_IMPORTC = "@import url(\"/KSK@test?type=text/css&maybecharset=UTF-8\");";

	private static final String CSS_IMPORT_TYPE = "@import url(\"/KSK@test?type=text/plain\");";
	private static final String CSS_IMPORT_TYPEC = "@import url(\"/KSK@test?type=text/css&maybecharset=UTF-8\");";

	private static final String CSS_IMPORT2 = "@import url(\"/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html\") screen;";
	private static final String CSS_IMPORT2C = "@import url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html?type=text/css&maybecharset=UTF-8\") screen;";

	private static final String CSS_IMPORT_MULTI_MEDIA = "@import url(\"/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html\") projection, tv;";
	private static final String CSS_IMPORT_MULTI_MEDIAC = "@import url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html?type=text/css&maybecharset=UTF-8\") projection, tv;";

	private static final String CSS_IMPORT_MULTI_MEDIA_BOGUS = "@import url(\"/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html\") projection, tvvv;";
	private static final String CSS_IMPORT_MULTI_MEDIA_BOGUSC = "@import url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html?type=text/css&maybecharset=UTF-8\") projection;";

	private static final String CSS_IMPORT_MULTI_MEDIA_ALL = "@import url(\"/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html\") all;";
	private static final String CSS_IMPORT_MULTI_MEDIA_ALLC = "@import url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html?type=text/css&maybecharset=UTF-8\") all;";

	private static final String CSS_IMPORT_SPACE_IN_STRING = "@import url(\"/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test page\") screen;";
	private static final String CSS_IMPORT_SPACE_IN_STRINGC = "@import url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page?type=text/css&maybecharset=UTF-8\") screen;";
	
	private static final String CSS_IMPORT_NOURL_TWOMEDIAS = "@import \"/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html\" screen tty;";
	private static final String CSS_IMPORT_NOURL_TWOMEDIASC = "@import url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/1-1.html?type=text/css&maybecharset=UTF-8\") screen, tty;";
	
	// Unquoted URL is invalid.
	private static final String CSS_IMPORT_UNQUOTED = "@import style.css;";
	
	// Quoted without url() is valid.
	private static final String CSS_IMPORT_NOURL = "@import \"style.css\";";
	private static final String CSS_IMPORT_NOURLC = "@import url(\"style.css?type=text/css&maybecharset=UTF-8\");";
	
	private static final String CSS_ESCAPED_LINK = "* { background: url(\\00002f\\00002fwww.google.co.uk/intl/en_uk/images/logo.gif); }\n";
	private static final String CSS_ESCAPED_LINKC = "* { }\n";
	
	private static final String CSS_ESCAPED_LINK2 = "* { background: url(\\/\\/www.google.co.uk/intl/en_uk/images/logo.gif); }\n";
	private static final String CSS_ESCAPED_LINK2C = "* { }\n";
	
	// CSS2.1 spec, 4.1.7
	private static final String CSS_DELETE_INVALID_SELECTOR = "h1, h2 {color: green }\nh3, h4 & h5 {color: red }\nh6 {color: black }\n";
	private static final String CSS_DELETE_INVALID_SELECTORC = "h1, h2 {color: green;}\nh6 {color: black;}\n";
	
	private static final String LATE_CHARSET = "h3 { color:red;}\n@charset \"UTF-8\";";
	private static final String LATE_CHARSETC = "h3 { color:red;}\n";
	
	private static final String WRONG_CHARSET = "@charset \"UTF-16\";";
	private static final String NONSENSE_CHARSET = "@charset \"idiot\";";
	
	private static final String LATE_BOM = "h3 { color:red;}\n\ufeffh4 { color:blue;}";
	private static final String LATE_BOMC = "h3 { color:red;}\nh4 { color:blue;}";
		
	private static final String BOM = "\ufeffh3 { color:red;}";
	
	private static final String COMMENT = "/* this is a comment */h1 { color: red;}";
	private static final String COMMENTC = "h1 { color: red;}";
	
	private static final String CSS_COMMA_WHITESPACE = "body { padding: 0px;\n}\n\nh1, h2, h3 {\nmargin: 0px;\n}";
	
	// Invalid media type
	
	private static final String CSS_INVALID_MEDIA_CASCADE = "@media blah { h1, h2 { color: green;} }";
	
	private final static LinkedHashMap<String, String> propertyTests = new LinkedHashMap<String, String>();
	static {
		// Check that the last part of a double bar works
		propertyTests.put("@media speech { h1 { azimuth: behind }; }", "@media speech { h1 { azimuth: behind;}}");
		
		propertyTests.put("h1 { color: red; rotation: 70minutes }", "h1 { color: red;}");
		propertyTests.put("@media screen { h1 { color: red; }\nh1[id=\"\n]}", "@media screen { h1 { color: red; }}");
		propertyTests.put("@media screen { h1 { color: red; }}", "@media screen { h1 { color: red; }}");
		propertyTests.put("p { color: green;\nfont-family: 'Courier New Times\ncolor: red;\ncolor: green;\n}", "p { color: green;\ncolor: green;\n}");
		propertyTests.put("p { font-family: 'Courier New Times\ncolor: red;\ncolor: green;\n}", "p {\ncolor: green;\n}");
		propertyTests.put("@media screen { h1[id=\"\n]}", "@media screen {}");
		
		propertyTests.put("td { background-position:bottom;}\n", "td { background-position:bottom;}\n");
		propertyTests.put("td { background:repeat-x;}\n", "td { background:repeat-x;}\n");
		
		// Double bar: recurse after recognising last element
		propertyTests.put("td { background:repeat-x no;}\n", "td { background:repeat-x no;}\n");
		propertyTests.put("td { background:repeat-x no transparent;}\n", "td { background:repeat-x no transparent;}\n");
		propertyTests.put("td { background:repeat-x no transparent scroll;}\n", "td { background:repeat-x no transparent scroll;}\n");
		
		propertyTests.put("@media speech { h1 { azimuth: 30deg }; }", "@media speech { h1 { azimuth: 30deg;}}");
		propertyTests.put("@media speech { h1 { azimuth: 0.877171rad }; }", "@media speech { h1 { azimuth: 0.877171rad;}}");
		propertyTests.put("@media speech { h1 { azimuth: left-side behind }; }", "@media speech { h1 { azimuth: left-side behind;}}");
		// Invalid combination
		propertyTests.put("@media speech { h1 { azimuth: left-side behind 30deg }; }", "@media speech { h1 {}}");
		propertyTests.put("@media speech { h1 { azimuth: inherit }; }", "@media speech { h1 { azimuth: inherit;}}");
		// Wrong media type
		propertyTests.put("h1 { azimuth: inherit }", "h1 {}");
		
		propertyTests.put("td { background-attachment: scroll}", "td { background-attachment: scroll;}");
		propertyTests.put("td { background-color: rgb(255, 255, 255)}", "td { background-color: rgb(255, 255, 255);}");
		propertyTests.put("td { background-color: #fff}", "td { background-color: #fff;}");
		propertyTests.put("td { background-color: #ffffff}", "td { background-color: #ffffff;}");
		propertyTests.put("td { background-color: rgb(100%,0%,50%)}", "td { background-color: rgb(100%,0%,50%);}");
		propertyTests.put("td { background-color: rgb(100%, 0%, 50%)}", "td { background-color: rgb(100%, 0%, 50%);}");
		// Values outside the standard RGB device gamut are allowed by the spec, they will be clipped and may be representible on some devices.
		propertyTests.put("td { background-color: rgb(300, 0, 0)}", "td { background-color: rgb(300, 0, 0);}");
		propertyTests.put("td { background-color: rgb(255, -10, 0)}", "td { background-color: rgb(255, -10, 0);}");
		propertyTests.put("td { background-color: rgb(110%, 0%, 0%)}", "td { background-color: rgb(110%, 0%, 0%);}");
		
		// Invalid element
		propertyTests.put("silly { background-attachment: scroll}", "");
		propertyTests.put("h3 { background-position: 30% top}", "h3 { background-position: 30% top;}");
		// Fractional lengths
		propertyTests.put("h3 { background-position: 3.3cm 20%}", "h3 { background-position: 3.3cm 20%;}");
		// Negative fractional lengths
		propertyTests.put("h3 { background-position: -0.87em 20%}", "h3 { background-position: -0.87em 20%;}");
		
		// Url with an encoded space
		propertyTests.put("h3 { background-image: url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\") }", "h3 { background-image: url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		// Url with a space
		propertyTests.put("h3 { background-image: url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test page\") }", "h3 { background-image: url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		// Url with lower case chk@
		propertyTests.put("h3 { background-image: url(\"/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\") }", "h3 { background-image: url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		
		// url without "" in properties
		propertyTests.put("h3 { background-image: url(/chk@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page) }", "h3 { background-image: url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		
		// Escapes in strings
		propertyTests.put("h3 { background-image: url(/KSK@\\something.png);}", "h3 { background-image: url(\"/KSK@something.png\");}");
		propertyTests.put("h3 { background-image: url(/KSK@\\something.png?type=image/png\\26 force=true);}", "h3 { background-image: url(\"/KSK@something.png?type=image/png\");}");
		propertyTests.put("h3 { background-image: url(/KSK@\\something.png?type=image/png\\000026force=true);}", "h3 { background-image: url(\"/KSK@something.png?type=image/png\");}");
		propertyTests.put("h3 { background-image: url(/KSK@\\\"something\\\".png?type=image/png\\000026force=true);}", "h3 { background-image: url(\"/KSK@%22something%22.png?type=image/png\");}");
		
		// Mixed background
		propertyTests.put("h3 { background: url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\") }", "h3 { background: url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		propertyTests.put("h3 { background: scroll url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\") }", "h3 { background: scroll url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		propertyTests.put("h3 { background: scroll #f00 url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\") }", "h3 { background: scroll #f00 url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		propertyTests.put("h3 { background: scroll #f00 url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\") }", "h3 { background: scroll #f00 url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		propertyTests.put("h3 { background: scroll rgb(100%, 2%, 1%) url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\") }", "h3 { background: scroll rgb(100%, 2%, 1%) url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		propertyTests.put("h3 { background: 3.3cm 20%;}", "h3 { background: 3.3cm 20%;}");
		propertyTests.put("h3 { background: scroll 3.3cm 20%;}", "h3 { background: scroll 3.3cm 20%;}");
		propertyTests.put("h3 { background: scroll rgb(100%, 2%, 1%) 3.3cm 20%;}", "h3 { background: scroll rgb(100%, 2%, 1%) 3.3cm 20%;}");
		propertyTests.put("h3 { background: 3.3cm 20% url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}", "h3 { background: 3.3cm 20% url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		propertyTests.put("h3 { background: scroll rgb(100%, 2%, 1%) 3.3cm 20% url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\") }", "h3 { background: scroll rgb(100%, 2%, 1%) 3.3cm 20% url(\"/CHK@~~vxVQDfC9m8sR~M9zWJQKzCxLeZRWy6T1pWLM2XX74,2LY7xwOdUGv0AeJ2WKRXZG6NmiUL~oqVLKnh3XdviZU,AAIC--8/test%20page\");}");
		// CSS escapes, url escapes, combinations of the two
		propertyTests.put("h3 { background: url(\"\\/\\/www.google.com/google.png\");}", "h3 {}");
		propertyTests.put("h3 { background: url(\"\\2f \\2f www.google.com/google.png\");}", "h3 {}");
		propertyTests.put("h3 { background: url(\"\\00002f\\00002fwww.google.com/google.png\");}", "h3 {}");
		propertyTests.put("h3 { background: url(\"%2f%2fwww.google.com/google.png\");}", "h3 {}");
		propertyTests.put("h3 { background: url(\"\\25 2f\\25 2fwww.google.com/google.png\");}", "h3 {}");
		
		// Counters
		propertyTests.put("table { counter-increment: counter1 1}", "table { counter-increment: counter1 1;}");
		// Counters with whacky identifiers
		propertyTests.put("table { counter-increment: c\\ounter1 1}", "table { counter-increment: c\\ounter1 1;}");
		propertyTests.put("table { counter-increment: c\\ ounter1 1}", "table { counter-increment: c\\ ounter1 1;}");
		propertyTests.put("table { counter-increment: c\\ \\}ounter1 1}", "table { counter-increment: c\\ \\}ounter1 1;}");
		propertyTests.put("table { counter-increment: c\\ \\}oun\\:ter1 1}", "table { counter-increment: c\\ \\}oun\\:ter1 1;}");
		propertyTests.put("table { counter-increment: c\\ \\}oun\\:ter1\\; 1}", "table { counter-increment: c\\ \\}oun\\:ter1\\; 1;}");
		propertyTests.put("table { counter-increment: \\2 \\32 \\ c\\ \\}oun\\:ter1\\; 1}", "table { counter-increment: \\2 \\32 \\ c\\ \\}oun\\:ter1\\; 1;}");
		propertyTests.put("table { counter-increment: \\000032\\2 \\32 \\ c\\ \\}oun\\:ter1\\; 1}", "table { counter-increment: \\000032\\2 \\32 \\ c\\ \\}oun\\:ter1\\; 1;}");
		
		// Varying the number of words matched by each occurrence in a double bar.
		propertyTests.put("table { counter-reset: mycounter 1 hiscounter myothercounter 2;}", "table { counter-reset: mycounter 1 hiscounter myothercounter 2;}");
		
		// Content tests
		propertyTests.put("h1 { content: \"string with spaces\" }", "h1 { content: \"string with spaces\";}");
		propertyTests.put("h1 { content: attr(\\ \\ attr\\ with\\ spaces) }", "h1 { content: attr(\\ \\ attr\\ with\\ spaces);}");
		propertyTests.put("h1 { content: \"string with spaces\" attr(\\ \\ attr\\ with\\ spaces) }", "h1 { content: \"string with spaces\" attr(\\ \\ attr\\ with\\ spaces);}");
		
		// Strip nulls
		propertyTests.put("h2 { color: red }", "h2 { color: red;}");
		propertyTests.put("h2 { color: red\0 }", "h2 { color: red;}");
		
		// Lengths must have a unit
		propertyTests.put("h2 { border-width: 1.5em;}\n","h2 { border-width: 1.5em;}\n");
		propertyTests.put("h2 { border-width: 12px;}\n","h2 { border-width: 12px;}\n");
		propertyTests.put("h2 { border-width: 1.5;}\n","h2 {}\n");
		propertyTests.put("h2 { border-width: 0;}\n","h2 { border-width: 0;}\n");
		propertyTests.put("h2 { border-width: 10;}\n","h2 {}\n");
		
		// Fonts
		propertyTests.put("h2 { font-family: times new roman;}\n", "h2 { font-family: times new roman;}\n");
		propertyTests.put("h2 { font-family: Times New Roman;}\n", "h2 { font-family: Times New Roman;}\n");
		propertyTests.put("h2 { font-family: \"Times New Roman\";}\n", "h2 { font-family: \"Times New Roman\";}\n");
		propertyTests.put("h2 { font-family: inherit;}\n", "h2 { font-family: inherit;}\n");
		propertyTests.put("h2 { font-family: \"Times New Roman\" , \"Arial\";}\n","h2 { font-family: \"Times New Roman\" , \"Arial\";}\n"); 
		propertyTests.put("h2 { font-family: \"Times New Roman\", \"Arial\";}\n","h2 { font-family: \"Times New Roman\", \"Arial\";}\n"); 
		propertyTests.put("h2 { font-family: \"Times New Roman\", \"Arial\", \"Helvetica\";}\n","h2 { font-family: \"Times New Roman\", \"Arial\", \"Helvetica\";}\n");
		propertyTests.put("h2 { font-family: \"Times New Roman\", Arial;}\n","h2 { font-family: \"Times New Roman\", Arial;}\n");
		propertyTests.put("h2 { font-family: Times New Roman, Arial;}\n","h2 { font-family: Times New Roman, Arial;}\n");
		propertyTests.put("h2 { font-family: serif, Times New Roman, Arial;}\n","h2 { font-family: serif, Times New Roman, Arial;}\n");
		propertyTests.put("h2 { font: Times New Roman;}\n", "h2 { font: Times New Roman;}\n");
		propertyTests.put("h2 { font: \"Times New Roman\";}\n", "h2 { font: \"Times New Roman\";}\n");
		propertyTests.put("h2 { font: medium \"Times New Roman\";}\n", "h2 { font: medium \"Times New Roman\";}\n");
		propertyTests.put("h2 { font: medium Times New Roman, Arial Black;}\n", "h2 { font: medium Times New Roman, Arial Black;}\n");
		propertyTests.put("h2 { font: italic small-caps 500 1.5em Times New Roman, Arial Black;}\n", "h2 { font: italic small-caps 500 1.5em Times New Roman, Arial Black;}\n");
		propertyTests.put("h2 { font: 1.5em/12pt Times New Roman, Arial Black;}\n", "h2 { font: 1.5em/12pt Times New Roman, Arial Black;}\n");
		propertyTests.put("h2 { font: small-caps 1.5em/12pt Times New Roman, Arial Black;}\n", "h2 { font: small-caps 1.5em/12pt Times New Roman, Arial Black;}\n");
		propertyTests.put("h2 { font: 500 1.5em/12pt Times New Roman, Arial Black;}\n", "h2 { font: 500 1.5em/12pt Times New Roman, Arial Black;}\n");
		propertyTests.put("h2 { font: small-caps 500 1.5em/12pt Times New Roman, Arial Black;}\n", "h2 { font: small-caps 500 1.5em/12pt Times New Roman, Arial Black;}\n");
		propertyTests.put("h2 { font: 500 \"Times New Roman\";}\n", "h2 { font: 500 \"Times New Roman\";}\n");
		propertyTests.put("h2 { font-weight: 500;}\n", "h2 { font-weight: 500;}\n");
		propertyTests.put("h2 { font: normal \"Times New Roman\";}\n", "h2 { font: normal \"Times New Roman\";}\n");
		propertyTests.put("h2 { font: 500 normal \"Times New Roman\";}\n", "h2 { font: 500 normal \"Times New Roman\";}\n");
		propertyTests.put("h2 { font: 500 normal Times New Roman;}\n", "h2 { font: 500 normal Times New Roman;}\n");
		propertyTests.put("h2 { font: 500 normal Times New Roman, Arial Black;}\n", "h2 { font: 500 normal Times New Roman, Arial Black;}\n");
		propertyTests.put("h2 { font: 500 normal 1.5em/12pt Times New Roman, Arial Black;}\n", "h2 { font: 500 normal 1.5em/12pt Times New Roman, Arial Black;}\n");
		propertyTests.put("h2 { font: small-caps 500 normal 1.5em/12pt Times New Roman, Arial Black;}\n", "h2 { font: small-caps 500 normal 1.5em/12pt Times New Roman, Arial Black;}\n");
		// There was a point where this worked, it's wrong.
		propertyTests.put("h2 { font: times 10pt new roman;}\n", "h2 {}\n");
		propertyTests.put("h2 { font: 10pt times new roman;}\n", "h2 { font: 10pt times new roman;}\n");
		
		// Space is not required either after or before comma!
		propertyTests.put("h2 { font-family: Verdana,sans-serif }", "h2 { font-family: Verdana,sans-serif;}");
		// Case in generic keywords
		propertyTests.put("h2 { font-family: Verdana,Sans-Serif }", "h2 { font-family: Verdana,Sans-Serif;}");
		// This one is from the text Activelink Index
		propertyTests.put("h2 { font: normal 12px/15px Verdana,sans-serif }", "h2 { font: normal 12px/15px Verdana,sans-serif;}");
		// From Activelink Index. This is invalid but browsers will probably change sans to sans-serif; and we will allow it as it might be a generic font family.
		propertyTests.put("h2 { font:normal 12px/15px Verdana,sans}", "h2 { font:normal 12px/15px Verdana,sans;}");
		// Some fonts that are not on the list but are syntactically valid
		propertyTests.put("h2 { font-family: Times New Reman;}\n", "h2 { font-family: Times New Reman;}\n");
		propertyTests.put("h2 { font-family: \"Times New Reman\";}\n", "h2 { font-family: \"Times New Reman\";}\n");
		propertyTests.put("h2 { font: Times New Reman;}\n", "h2 { font: Times New Reman;}\n");
		propertyTests.put("h2 { font-family: zaphod beeblebrox,bitstream vera sans,arial,sans-serif}", "h2 { font-family: zaphod beeblebrox,bitstream vera sans,arial,sans-serif;}");
		// NOT syntactically valid
		propertyTests.put("h2 { font-family: http://www.badguy.com/myfont.ttf}", "h2 {}");
		propertyTests.put("h2 { font-family: times new roman,arial,verdana }", "h2 { font-family: times new roman,arial,verdana;}");
		
		// Short percentage value
		propertyTests.put("body { bottom: 1%;}", "body { bottom: 1%;}");
		// Shape
		propertyTests.put("p { clip: rect(5px, 40px, 45px, 5px); }", "p { clip: rect(5px, 40px, 45px, 5px); }");
		propertyTests.put("p { clip: rect(auto, auto, 45px, 5px); }", "p { clip: rect(auto, auto, 45px, 5px); }");
	}
	
	MIMEType cssMIMEType;
	
	public void setUp() throws InvalidThresholdException {
		new NodeL10n();
    	Logger.setupStdoutLogging(Logger.MINOR, "freenet.clients.http.filter:DEBUG");
    	ContentFilter.init();
    	cssMIMEType = ContentFilter.getMIMEType("text/css");
	}
	
	public void testCSS1Selector() throws IOException, URISyntaxException {


		Collection c = CSS1_SELECTOR.keySet();
		Iterator itr = c.iterator();
		while(itr.hasNext())
		{

			String key=itr.next().toString();
			String value=CSS1_SELECTOR.get(key);
			assertTrue("key=\""+key+"\" value=\""+filter(key)+"\" should be \""+value+"\"", filter(key).contains(value));
		}

		assertTrue("key=\""+CSS_DELETE_INVALID_SELECTOR+"\" value=\""+filter(CSS_DELETE_INVALID_SELECTOR)+"\" should be \""+CSS_DELETE_INVALID_SELECTORC+"\"", CSS_DELETE_INVALID_SELECTORC.equals(filter(CSS_DELETE_INVALID_SELECTOR)));
		assertTrue("key=\""+CSS_INVALID_MEDIA_CASCADE+"\" value=\""+filter(CSS_INVALID_MEDIA_CASCADE)+"\"", "".equals(filter(CSS_INVALID_MEDIA_CASCADE)));
	}

	public void testCSS2Selector() throws IOException, URISyntaxException {
		Collection c = CSS2_SELECTOR.keySet();
		Iterator itr = c.iterator();
		int i=0; 
		while(itr.hasNext())
		{
			String key=itr.next().toString();
			String value=CSS2_SELECTOR.get(key);
			System.err.println("Test "+(i++)+" : "+key+" -> "+value);
			assertTrue("key="+key+" value="+filter(key)+"\" should be \""+value+"\"", filter(key).contains(value));
		}

		i=0;
		for(String key : CSS2_BAD_SELECTOR) {
			System.err.println("Bad selector test "+(i++));
			assertTrue("".equals(filter(key)));
		}
		
	}

	public void testNewlines() throws IOException, URISyntaxException {
		assertTrue("key=\""+CSS_STRING_NEWLINES+"\" value=\""+filter(CSS_STRING_NEWLINES)+"\" should be: \""+CSS_STRING_NEWLINESC+"\"", CSS_STRING_NEWLINESC.equals(filter(CSS_STRING_NEWLINES)));
	}
	
	public void testBackgroundURL() throws IOException, URISyntaxException {
		assertTrue("key="+CSS_BACKGROUND_URL+" value=\""+filter(CSS_BACKGROUND_URL)+"\" should be \""+CSS_BACKGROUND_URLC+"\"", CSS_BACKGROUND_URLC.equals(filter(CSS_BACKGROUND_URL)));
		
		assertTrue("key="+CSS_LCASE_BACKGROUND_URL+" value=\""+filter(CSS_LCASE_BACKGROUND_URL)+"\"", CSS_LCASE_BACKGROUND_URLC.equals(filter(CSS_LCASE_BACKGROUND_URL)));
	}
	
	public void testImports() throws IOException, URISyntaxException {
		assertTrue("key="+CSS_IMPORT+" value=\""+filter(CSS_IMPORT)+"\"", CSS_IMPORTC.equals(filter(CSS_IMPORT)));
		assertTrue("key="+CSS_IMPORT2+" value=\""+filter(CSS_IMPORT2)+"\"", CSS_IMPORT2C.equals(filter(CSS_IMPORT2)));
		assertTrue("key="+CSS_IMPORT_MULTI_MEDIA+" value=\""+filter(CSS_IMPORT_MULTI_MEDIA)+"\"", CSS_IMPORT_MULTI_MEDIAC.equals(filter(CSS_IMPORT_MULTI_MEDIA)));
		assertTrue("key="+CSS_IMPORT_MULTI_MEDIA_BOGUS+" value=\""+filter(CSS_IMPORT_MULTI_MEDIA_BOGUS)+"\"", CSS_IMPORT_MULTI_MEDIA_BOGUSC.equals(filter(CSS_IMPORT_MULTI_MEDIA_BOGUS)));
		assertTrue("key="+CSS_IMPORT_MULTI_MEDIA_ALL+" value=\""+filter(CSS_IMPORT_MULTI_MEDIA_ALL)+"\"", CSS_IMPORT_MULTI_MEDIA_ALLC.equals(filter(CSS_IMPORT_MULTI_MEDIA_ALL)));
		assertTrue("key="+CSS_IMPORT_TYPE+" value=\""+filter(CSS_IMPORT_TYPE)+"\"", CSS_IMPORT_TYPEC.equals(filter(CSS_IMPORT_TYPE)));
		assertTrue("key="+CSS_IMPORT_SPACE_IN_STRING+" value=\""+filter(CSS_IMPORT_SPACE_IN_STRING)+"\"", CSS_IMPORT_SPACE_IN_STRINGC.equals(filter(CSS_IMPORT_SPACE_IN_STRING)));
		assertTrue("key="+CSS_IMPORT_NOURL_TWOMEDIAS+" value=\""+filter(CSS_IMPORT_NOURL_TWOMEDIAS)+"\"", CSS_IMPORT_NOURL_TWOMEDIASC.equals(filter(CSS_IMPORT_NOURL_TWOMEDIAS)));
		assertTrue("key="+CSS_IMPORT_UNQUOTED+" should be empty", "".equals(filter(CSS_IMPORT_UNQUOTED)));
		assertTrue("key="+CSS_IMPORT_NOURL+" value=\""+filter(CSS_IMPORT_NOURL)+"\"", CSS_IMPORT_NOURLC.equals(filter(CSS_IMPORT_NOURL)));
	}
	
	public void testEscape() throws IOException, URISyntaxException {
		assertTrue("key="+CSS_ESCAPED_LINK+" value=\""+filter(CSS_ESCAPED_LINK)+"\"", CSS_ESCAPED_LINKC.equals(filter(CSS_ESCAPED_LINK)));
		assertTrue("key="+CSS_ESCAPED_LINK2+" value=\""+filter(CSS_ESCAPED_LINK2)+"\"", CSS_ESCAPED_LINK2C.equals(filter(CSS_ESCAPED_LINK2)));
	}
	
	public void testProperties() throws IOException, URISyntaxException {
		for(Entry<String, String> entry : propertyTests.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			assertTrue("key=\""+key+"\" encoded=\""+filter(key)+"\" should be \""+value+"\"", value.equals(filter(key)));
		}
	}
	
	private String filter(String css) throws IOException, URISyntaxException {
		StringWriter w = new StringWriter();
		GenericReadFilterCallback cb = new GenericReadFilterCallback(new URI("/CHK@OR904t6ylZOwoobMJRmSn7HsPGefHSP7zAjoLyenSPw,x2EzszO4Kqot8akqmKYXJbkD-fSj6noOVGB-K2YisZ4,AAIC--8/1-works.html"), null);
		CSSParser p = new CSSParser(new StringReader(css), w, false, cb, "UTF-8", false);
		p.parse();
		return w.toString();
	}
	
	public void testCharset() throws IOException, URISyntaxException {
		// Test whether @charset is passed through when it is correct.
		String test = "@charset \"UTF-8\";\nh2 { color: red;}";
		assertTrue("key=\""+test+"\" value=\""+filter(test)+"\"", filter(test).equals(test));
		// No quote marks
		String testUnquoted = "@charset UTF-8;\nh2 { color: red;}";
		assertTrue("key=\""+test+"\" value=\""+filter(test)+"\"", filter(testUnquoted).equals(test));
		// Test whether the parse fails when @charset is not correct.
		String testFail = "@charset ISO-8859-1;\nh2 { color: red;};";
		try {
			filter(test).equals("");
			assertFalse("Bogus @charset should have been deleted, but result is \""+filter(testFail)+"\"", false);
		} catch (IOException e) {
			// Ok.
		}
		// Test charset extraction
		getCharsetTest("UTF-8");
		getCharsetTest("UTF-16BE");
		getCharsetTest("UTF-16LE");
		getCharsetTest("UTF-32BE");
		getCharsetTest("UTF-32LE");
		
		getCharsetTest("ISO-8859-1", "UTF-8");
		getCharsetTest("ISO-8859-15", "UTF-8");
		// FIXME add more ascii-based code pages?
		
		// IBM 1141-1144, 1147, 1149 do not use the same EBCDIC codes for the basic english alphabet.
		// But we can support these four EBCDIC variants.
		
		getCharsetTest("IBM01140");
		getCharsetTest("IBM01145", "IBM01140");
		getCharsetTest("IBM01146", "IBM01140");
		getCharsetTest("IBM01148", "IBM01140");
		
		getCharsetTest("IBM1026");
		
		// Some unsupported charsets. These should not get through the filter.
		
		charsetTestUnsupported("IBM01141");
		charsetTestUnsupported("IBM01142");
		charsetTestUnsupported("IBM01143");
		charsetTestUnsupported("IBM01144");
		charsetTestUnsupported("IBM01147");
		charsetTestUnsupported("IBM01149");
		
		// Late charset is invalid
		assertTrue("key="+LATE_CHARSET+" value=\""+filter(LATE_CHARSET)+"\"", LATE_CHARSETC.equals(filter(LATE_CHARSET)));
		try {
			String output = filter(WRONG_CHARSET);
			assertFalse("Should complain that detected charset differs from real charset, but returned \""+output+"\"", true);
		} catch (IOException e) {
			// Ok.
			// FIXME should have a dedicated exception.
		}
		try {
			String output = filter(NONSENSE_CHARSET);
			assertFalse("wrong charset output is \""+output+"\" but it should throw!", true);
		} catch (UnsupportedCharsetInFilterException e) {
			// Ok.
		}
		
		assertTrue(BOM.equals(filter(BOM)));
		assertTrue("output=\""+filter(LATE_BOM)+"\"",LATE_BOMC.equals(filter(LATE_BOM)));
	}
	
	private void getCharsetTest(String charset) throws DataFilterException, IOException, URISyntaxException {
		getCharsetTest(charset, null);
	}
	
	private void getCharsetTest(String charset, String family) throws DataFilterException, IOException, URISyntaxException {
		String original = "@charset \""+charset+"\";\nh2 { color: red;}";
		byte[] bytes = original.getBytes(charset);
		CSSReadFilter filter = new CSSReadFilter();
		SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(bytes);
		// Detect with original charset.
		String detectedCharset = filter.getCharset(bucket, charset);
		assertTrue("Charset detected \""+detectedCharset+"\" should be \""+charset+"\" even when parsing with correct charset", charset.equalsIgnoreCase(detectedCharset));
		BOMDetection bom = filter.getCharsetByBOM(bucket);
		String bomCharset = detectedCharset = bom == null ? null : bom.charset;
		assertTrue("Charset detected \""+detectedCharset+"\" should be \""+charset+"\" or \""+family+"\" from getCharsetByBOM", detectedCharset == null || charset.equalsIgnoreCase(detectedCharset) || (family != null && family.equalsIgnoreCase(detectedCharset)));
		detectedCharset = ContentFilter.detectCharset(bucket, cssMIMEType, null);
		assertTrue("Charset detected \""+detectedCharset+"\" should be \""+charset+"\" from ContentFilter.detectCharset bom=\""+bomCharset+"\"", charset.equalsIgnoreCase(detectedCharset));
		FilterOutput fo = ContentFilter.filter(bucket, new ArrayBucketFactory(), "text/css", new URI("/CHK@OR904t6ylZOwoobMJRmSn7HsPGefHSP7zAjoLyenSPw,x2EzszO4Kqot8akqmKYXJbkD-fSj6noOVGB-K2YisZ4,AAIC--8/1-works.html"), null, null);
		assertTrue("ContentFilter.filter() returned wrong charset \""+fo.type+"\" should be \""+charset+"\"", fo.type.equalsIgnoreCase("text/css; charset="+charset));
		String filtered = new String(BucketTools.toByteArray(fo.data), charset);
		assertTrue("ContentFilter.filter() returns \""+filtered+"\" not original \""+original+"\" for charset \""+charset+"\"", original.equals(filtered));
	}
	
	private void charsetTestUnsupported(String charset) throws DataFilterException, IOException, URISyntaxException {
		String original = "@charset \""+charset+"\";\nh2 { color: red;}";
		byte[] bytes = original.getBytes(charset);
		CSSReadFilter filter = new CSSReadFilter();
		SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(bytes);
		String detectedCharset;
		BOMDetection bom = filter.getCharsetByBOM(bucket);
		String bomCharset = detectedCharset = bom == null ? null : bom.charset;
		assertTrue("Charset detected \""+detectedCharset+"\" should be unknown testing unsupported charset \""+charset+"\" from getCharsetByBOM", detectedCharset == null);
		detectedCharset = ContentFilter.detectCharset(bucket, cssMIMEType, null);
		assertTrue("Charset detected \""+detectedCharset+"\" should be unknown testing unsupported charset \""+charset+"\" from ContentFilter.detectCharset bom=\""+bomCharset+"\"", charset == null || "utf-8".equalsIgnoreCase(detectedCharset));
		try {
			FilterOutput fo = ContentFilter.filter(bucket, new ArrayBucketFactory(), "text/css", new URI("/CHK@OR904t6ylZOwoobMJRmSn7HsPGefHSP7zAjoLyenSPw,x2EzszO4Kqot8akqmKYXJbkD-fSj6noOVGB-K2YisZ4,AAIC--8/1-works.html"), null, null);
			// It is safe to return utf-8, as long as we clobber the actual content; utf-8 is the default, but other stuff decoded to it is unlikely to be coherent...
			assertTrue("ContentFilter.filter() returned charset \""+fo.type+"\" should be unknown testing unsupported charset \""+charset+"\"", fo.type.equalsIgnoreCase("text/css; charset="+charset) || fo.type.equalsIgnoreCase("text/css; charset=utf-8"));
			String filtered = new String(BucketTools.toByteArray(fo.data), charset);
			assertTrue("ContentFilter.filter() returns something: \""+filtered+"\" should be empty as unsupported charset, original: \""+original+"\" for charset \""+charset+"\"", filtered.equals(""));
		} catch (UnsupportedCharsetInFilterException e) {
			// Ok.
		} catch (IOException e) {
			// Ok.
		}
		
	}
	
	public void testMaybeCharset() throws UnsafeContentTypeException, URISyntaxException, IOException {
		testUseMaybeCharset("UTF-8");
		testUseMaybeCharset("UTF-16");
		testUseMaybeCharset("UTF-32LE");
		testUseMaybeCharset("IBM01140");
	}
	
	private void testUseMaybeCharset(String charset) throws URISyntaxException, UnsafeContentTypeException, IOException {
		String original = "h2 { color: red;}";
		byte[] bytes = original.getBytes(charset);
		SimpleReadOnlyArrayBucket bucket = new SimpleReadOnlyArrayBucket(bytes);
		FilterOutput fo = ContentFilter.filter(bucket, new ArrayBucketFactory(), "text/css", new URI("/CHK@OR904t6ylZOwoobMJRmSn7HsPGefHSP7zAjoLyenSPw,x2EzszO4Kqot8akqmKYXJbkD-fSj6noOVGB-K2YisZ4,AAIC--8/1-works.html"), null, charset);
		assertTrue("ContentFilter.filter() returned wrong charset with maybeCharset: \""+fo.type+"\" should be \""+charset+"\"", fo.type.equalsIgnoreCase("text/css; charset="+charset));
		String filtered = new String(BucketTools.toByteArray(fo.data), charset);
		assertTrue("ContentFilter.filter() returns \""+filtered+"\" not original \""+original+"\" with maybeCharset \""+charset+"\"", original.equals(filtered));
	}
	
	public void testComment() throws IOException, URISyntaxException {
		assertTrue("value=\""+filter(COMMENT)+"\"",COMMENTC.equals(filter(COMMENT)));
	}
	
	public void testWhitespace() throws IOException, URISyntaxException {
		assertTrue("value=\""+filter(CSS_COMMA_WHITESPACE)+"\"", CSS_COMMA_WHITESPACE.equals(filter(CSS_COMMA_WHITESPACE)));
	}
}
