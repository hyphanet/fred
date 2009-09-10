package freenet.clients.http.filter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Collection;
import java.util.Iterator;
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


	public void testCSS1Selector() throws IOException {


		Collection c = CSS1_SELECTOR.keySet();
		Iterator itr = c.iterator();
		while(itr.hasNext())
		{

			String key=itr.next().toString();
			String value=CSS1_SELECTOR.get(key);
			assertTrue(filter(key).contains(value));
		}


	}

	public void testCSS2Selector() throws IOException {
		Collection c = CSS2_SELECTOR.keySet();
		Iterator itr = c.iterator();
		int i=0; 
		while(itr.hasNext())
		{

			String key=itr.next().toString();
			String value=CSS2_SELECTOR.get(key);
			assertTrue(filter(key).contains(value));
		}

	}

	private String filter(String css) throws IOException {
		StringWriter w = new StringWriter();
		CSSParser p = new CSSParser(new StringReader(css), w, false, null);
		p.parse();
		return w.toString();
	}
}
