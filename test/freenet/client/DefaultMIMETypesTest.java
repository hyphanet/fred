package freenet.client;

import junit.framework.TestCase;

public class DefaultMIMETypesTest extends TestCase {
	
	public void testFullList() {
		for(String mimeType : DefaultMIMETypes.getMIMETypes()) {
			assertTrue("Failed: \""+mimeType+"\"", DefaultMIMETypes.isPlausibleMIMEType(mimeType));
		}
	}
	
	public void testParams() {
		assertTrue(DefaultMIMETypes.isPlausibleMIMEType("text/xhtml+xml; charset=ISO-8859-1; blah=blah"));
		assertTrue(DefaultMIMETypes.isPlausibleMIMEType("multipart/mixed; boundary=\"---this is a silly boundary---\""));
	}

}
