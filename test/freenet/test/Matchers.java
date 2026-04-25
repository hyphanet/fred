package freenet.test;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class Matchers {

	/**
	 * Parses a {@link String} as HTML and verifies its content against the
	 * given {@link Element} matcher.
	 * <h2>Usage</h2>
	 * <p>
	 * Asserts that a string contains an &lt;html> element with a nested
	 * &lt;body> element:
	 * </p>
	 * <pre>
	 * String someHtml = generateHtml();
	 * assertThat(someHtml, isHtml(withElement("html body")));
	 * </pre>
	 *
	 * @param elementMatcher The matcher for the HTML element
	 * @return A matcher for a {@link String} containing HTML
	 * @see #withElement(String) for matching elements
	 */
	public static Matcher<String> isHtml(Matcher<Element> elementMatcher) {
		return new TypeSafeDiagnosingMatcher<String>() {
			@Override
			protected boolean matchesSafely(String html, Description mismatchDescription) {
				org.jsoup.nodes.Document document = Jsoup.parse(html);
				if (!elementMatcher.matches(document)) {
					elementMatcher.describeMismatch(document, mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("html matching ").appendDescriptionOf(elementMatcher);
			}
		};
	}

	/**
	 * Matches an element, if the given selector returns at least one match.
	 *
	 * @param selector The selector to match
	 * @return A matcher for the given selector
	 * @see <a href="https://jsoup.org/apidocs/org/jsoup/select/Selector.html">Selector syntax</a>
	 */
	public static Matcher<Element> withElement(String selector) {
		return new TypeSafeDiagnosingMatcher<Element>() {
			@Override
			protected boolean matchesSafely(Element element, Description mismatchDescription) {
				Elements elements = element.select(selector);
				if (elements.isEmpty()) {
					mismatchDescription.appendText("no element matching ").appendValue(selector);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("element matching ").appendValue(selector);
			}
		};
	}

}
