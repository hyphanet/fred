package freenet.clients.http;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Test cases for the RSS feed sniffing logic in FProxyToadlet.
 */
public class RssSniffTest {
    private static final String[] POSITIVE = {
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<rss xmlns:atom=\"http://www.w3.org/2005/Atom\" version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">",
        "<?xml?><!-- <rss> --><rss>",
        "<!DOCTYPE html><rss>bogus",
        "<rdf:RDF",
        "<rdf:RDFbogus",
        "<rdf:RDF<!--bogus-->",
        "<!DOCTYPE html>bogus ... <!-- more bogus --><?xml version><rss"
    };
    private static final String[] NEGATIVE = {
        "<?xml version=\"1.0\" encoding=\"utf-8\"?<rss xmlns:atom=\"http://www.w3.org/2005/Atom\" version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">",
        "<?xml?><!-- <rss> --><? <rss><bogus><rss>", // <bogus> is top-level
        "<!DOCTYPE html><bogus><rss", // <bogus> is top-level
        "<!rdf:RDF", // comment
        "<bogus><rdf:RDFbogus", // <bogus> is top-level
        "<!--<rdf:RDF>bogus-->", // comment
        "<!DOCTYPE html>bogus ... <!-- more bogus --><?xml version><pdf><rss" // <pdf> is top-level
    };

    @Test
    public void testRssSniffingPositive() {
        for (String tc : POSITIVE) {
            assertTrue(tc, FProxyToadlet.isSniffedAsFeed(tc.getBytes()));
        }
    }

    @Test
    public void testRssSniffingNegative() {
        for (String tc : NEGATIVE) {
            assertFalse(tc, FProxyToadlet.isSniffedAsFeed(tc.getBytes()));
        }
    }
}
