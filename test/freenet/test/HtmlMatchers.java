package freenet.test;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * That class contains {@link Matcher} implementations that can verify
 * certain properties of an HTML {@link Document}.
 */
public class HtmlMatchers {

	/**
	 * Returns a {@link Matcher} for a {@link Document} which verifies that
	 * the document has a title, and then verifies the title against the given
	 * matcher.
	 *
	 * @param titleMatcher A matcher for the title, if present
	 * @return A matcher for a {@link Document}
	 */
	public static Matcher<Document> hasTitle(Matcher<? super String> titleMatcher) {
		return new TypeSafeDiagnosingMatcher<Document>() {
			@Override
			protected boolean matchesSafely(Document document, Description mismatchDescription) {
				Element titleElement = document.select("head title").first();
				if (titleElement == null) {
					mismatchDescription.appendText("no title present");
					return false;
				}
				if (!titleMatcher.matches(titleElement.text())) {
					mismatchDescription.appendText("title ");
					titleMatcher.describeMismatch(titleElement.text(), mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has title matching ").appendDescriptionOf(titleMatcher);
			}
		};
	}

}
