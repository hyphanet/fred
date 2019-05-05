package freenet.clients.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.Test;

/**
 * Test cases for the RSS feed sniffing logic in FProxyToadlet.
 */
public class RssSnifferTest {

	@Test
	public void correctTopLevelRssTagIsRecognized() {
		String topLevelRssTag = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
				                        + "<rss xmlns:atom=\"http://www.w3.org/2005/Atom\" version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">";
		assertThat(topLevelRssTag, isSniffedAsFeed());
	}

	@Test
	public void rssTagAfterCommentIsRecognized() {
		String rssTagAfterComment = "<?xml?><!-- <rss> --><rss>";
		assertThat(rssTagAfterComment, isSniffedAsFeed());
	}

	@Test
	public void rssTagAfterDocTypeIsRecognized() {
		String rssTagAfterDocType = "<!DOCTYPE html><rss>bogus";
		assertThat(rssTagAfterDocType, isSniffedAsFeed());
	}

	@Test
	public void rdfTagInNamespaceIsRecognized() {
		String rdfTagInNamespace = "<rdf:RDF";
		assertThat(rdfTagInNamespace, isSniffedAsFeed());
	}

	@Test
	public void anyTagInRdfNamespaceStartingWithRdfIsRecognized() {
		String tagInRdfNamespaceWithNameStartingWithRdf = "<rdf:RDFbogus";
		assertThat(tagInRdfNamespaceWithNameStartingWithRdf, isSniffedAsFeed());
	}

	@Test
	public void rdfTagInNamespaceWithCommentIsRecognized() {
		String rdfTagWithNamespace = "<rdf:RDF<!--bogus-->";
		assertThat(rdfTagWithNamespace, isSniffedAsFeed());
	}

	@Test
	public void rssTagAfterDocTypeAndBogusTextNodesIsRecognized() {
		String rssTag = "<!DOCTYPE html>bogus ... <!-- more bogus --><?xml version><rss";
		assertThat(rssTag, isSniffedAsFeed());
	}

	@Test
	public void invalidRssTagIsNotRecognized() {
		String invalidRssTag = "<?xml version=\"1.0\" encoding=\"utf-8\"?<rss xmlns:atom=\"http://www.w3.org/2005/Atom\" version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">";
		assertThat(invalidRssTag, not(isSniffedAsFeed()));
	}

	@Test
	public void nonTopLevelRssTagIsNotRecognized() {
		String nonTopLevelRssTag = "<?xml?><!-- <rss> --><? <rss><bogus><rss>";
		assertThat(nonTopLevelRssTag, not(isSniffedAsFeed()));
	}

	@Test
	public void nonTopLevelRssTagAfterDocTypeIsNotRecognized() {
		String nonTopLevelRssTag = "<!DOCTYPE html><bogus><rss";
		assertThat(nonTopLevelRssTag, not(isSniffedAsFeed()));
	}

	@Test
	public void commentIsNotMistakenAsRdfTag() {
		String commentNode = "<!rdf:RDF";
		assertThat(commentNode, not(isSniffedAsFeed()));
	}

	@Test
	public void nonTopLevelRdfTagIsNotRecognized() {
		String nonTopLevelRdfTag = "<bogus><rdf:RDFbogus";
		assertThat(nonTopLevelRdfTag, not(isSniffedAsFeed()));
	}

	@Test
	public void rdfTagInCommentIsNotRecognized() {
		String commentNode = "<!--<rdf:RDF>bogus-->";
		assertThat(commentNode, not(isSniffedAsFeed()));
	}

	@Test
	public void nonTopLevelRssTagAfterDocTypeAndBogusTextNodesIsNotRecognized() {
		String nonTopLevelRssTag = "<!DOCTYPE html>bogus ... <!-- more bogus --><?xml version><pdf><rss";
		assertThat(nonTopLevelRssTag, not(isSniffedAsFeed()));
	}

	private Matcher<String> isSniffedAsFeed() {
		return new TypeSafeDiagnosingMatcher<String>() {
			@Override
			protected boolean matchesSafely(String item, Description mismatchDescription) {
				if (!RssSniffer.isSniffedAsFeed(item.getBytes(UTF_8))) {
					mismatchDescription.appendValue(item).appendText(" was not sniffed as feed");
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("is sniffed as feed");
			}
		};
	}

}
