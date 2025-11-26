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

	/**
	 * Returns a {@link Matcher} for a {@link Document} that locates an
	 * {@link Element} using a {@link Document#select(String) selector}
	 * and matches if at least one such {@link Element} can be found.
	 *
	 * @param selector The selector for the element to locate
	 * @return A matcher for a {@link Document}
	 */
	public static Matcher<Document> hasElement(String selector) {
		return new TypeSafeDiagnosingMatcher<Document>() {
			@Override
			protected boolean matchesSafely(Document document, Description mismatchDescription) {
				Element element = document.select(selector).first();
				if (element == null) {
					mismatchDescription.appendText("not found: ").appendValue(selector);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has element ").appendValue(selector);
			}
		};
	}

}
