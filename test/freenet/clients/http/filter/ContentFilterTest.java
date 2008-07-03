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
	// @see bug #710
	private static final String ANCHOR_TEST = "<a href=\"#test\" />";
	// @see bug #2451
	private static final String POUNT_CHARACTER_ENCODING_TEST = "<a href=\"/CHK@nvrrZF-qG7gInnxb2PUFNcNfgmdg2QHFQUsJGUzvUdE,nzsrkOSwJDP5lMod~kNDChDh96f1vIqGejOcMZpmIq0,AAEC--8/Ward Churchill - [2001] In a Pig's Eye - CD 2 - 07 - #1 Security Threat.ogg\" />";
	private static final String POUNT_CHARACTER_ENCODING_TEST_RESULT = "<a href=\"/CHK@nvrrZF-qG7gInnxb2PUFNcNfgmdg2QHFQUsJGUzvUdE,nzsrkOSwJDP5lMod~kNDChDh96f1vIqGejOcMZpmIq0,AAEC--8/Ward%20Churchill%20-%20%5b2001%5d%20In%20a%20Pig%27s%20Eye%20-%20CD%202%20-%2007%20-%20%231%20Security%20Threat.ogg\" />";

	private final BucketFactory bf = new ArrayBucketFactory();

	public void testHTMLFilter() throws Exception {
		assertEquals(ANCHOR_TEST, HTMLFilter(ANCHOR_TEST));
		assertEquals(POUNT_CHARACTER_ENCODING_TEST_RESULT, HTMLFilter(POUNT_CHARACTER_ENCODING_TEST));
	}
		
	private String HTMLFilter(String data) throws Exception {
		String typeName = "text/html";
		URI baseURI = new URI("http://localhost:8888/");
		
		return ContentFilter.filter(new ArrayBucket(data.getBytes("UTF-8")), bf, typeName, baseURI, null).data.toString();
	}
}
