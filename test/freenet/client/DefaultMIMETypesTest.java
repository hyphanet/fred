package freenet.client;

import static org.junit.Assert.*;

import org.junit.Test;

public class DefaultMIMETypesTest {

    @Test
    public void testFullList() {
        for (String mimeType : DefaultMIMETypes.getMIMETypes()) {
            assertTrue("Failed: \"" + mimeType + "\"", DefaultMIMETypes.isPlausibleMIMEType(mimeType));
        }
    }

    @Test
    public void testParams() {
        assertTrue(DefaultMIMETypes.isPlausibleMIMEType("text/xhtml+xml; charset=ISO-8859-1; blah=blah"));
        assertTrue(DefaultMIMETypes.isPlausibleMIMEType("multipart/mixed; boundary=\"---this is a silly boundary---\""));
    }

}
