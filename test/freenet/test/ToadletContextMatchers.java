package freenet.test;

import freenet.clients.http.TestToadletContext;
import freenet.clients.http.ToadletContext;
import java.util.List;
import java.util.Map;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Matchers for certain properties of a {@link TestToadletContext}. As
 * {@link TestToadletContext}s are only used during, it is not possible to
 * verify real {@link ToadletContext}s with it.
 */
public class ToadletContextMatchers {

	/**
	 * Returns a {@link Matcher} that verifies that a status has been set on
	 * the {@link TestToadletContext}, and then verifies it using the given
	 * {@link Matcher}.
	 *
	 * @param statusCodeMatcher The matcher for the status code, if present
	 * @return A matcher for a {@link TestToadletContext}
	 */
	public static Matcher<TestToadletContext> hasStatus(Matcher<? super Integer> statusCodeMatcher) {
		return new TypeSafeDiagnosingMatcher<TestToadletContext>() {
			@Override
			protected boolean matchesSafely(TestToadletContext toadletContext, Description mismatchDescription) {
				if (toadletContext.getStatusCode() == -1) {
					mismatchDescription.appendText("no status set");
					return false;
				}
				if (!statusCodeMatcher.matches(toadletContext.getStatusCode())) {
					mismatchDescription.appendText("status code ");
					statusCodeMatcher.describeMismatch(toadletContext.getStatusCode(), mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has status matching ").appendDescriptionOf(statusCodeMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} for a {@link TestToadletContext} which
	 * verifies that a {@code Content-Type} header has been set, and then
	 * verifies the header using the given {@link MimeType} matcher.
	 *
	 * @param mimeTypeMatcher The matcher for the MIME type, if present
	 * @return A matcher for a {@link TestToadletContext}
	 */
	public static Matcher<TestToadletContext> hasContentType(Matcher<? super MimeType> mimeTypeMatcher) {
		return new TypeSafeDiagnosingMatcher<TestToadletContext>() {
			@Override
			protected boolean matchesSafely(TestToadletContext toadletContext, Description mismatchDescription) {
				if (!toadletContext.getResponseHeaders().containsKey("content-type")) {
					mismatchDescription.appendText("no content type set");
					return false;
				}
				try {
					MimeType mimeType = new MimeType(toadletContext.getResponseHeaders().get("content-type").get(0));
					if (!mimeTypeMatcher.matches(mimeType)) {
						mismatchDescription.appendText("content type ");
						mimeTypeMatcher.describeMismatch(mimeType, mismatchDescription);
						return false;
					}
				} catch (MimeTypeParseException e) {
					throw new RuntimeException(e);
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has content type matching ").appendDescriptionOf(mimeTypeMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} that verifies that a body has been set on the
	 * {@link TestToadletContext}, and then verifies it using the given
	 * {@link Matcher}.
	 *
	 * @param bodyTextMatcher The matcher for the body text, if present
	 * @return A matcher for a {@link TestToadletContext}
	 */
	public static Matcher<TestToadletContext> hasBodyText(Matcher<? super String> bodyTextMatcher) {
		return new TypeSafeDiagnosingMatcher<TestToadletContext>() {
			@Override
			protected boolean matchesSafely(TestToadletContext toadletContext, Description mismatchDescription) {
				if (toadletContext.getBodyText() == null) {
					mismatchDescription.appendText("no body text present");
					return false;
				}
				if (!bodyTextMatcher.matches(toadletContext.getBodyText())) {
					mismatchDescription.appendText("body text ");
					bodyTextMatcher.describeMismatch(toadletContext.getBodyText(), mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has text body matching ").appendDescriptionOf(bodyTextMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} that uses the given matcher on the given
	 * header’s values. This matcher assumes that the response headers of
	 * the {@link TestToadletContext} have already been written; if they
	 * have not yet been written, this matcher will not match.
	 *
	 * @param name The name of the header to match
	 * @param valueMatcher A matcher for the header’s values
	 * @return A matcher for a {@link TestToadletContext}
	 */
	public static Matcher<TestToadletContext> hasHeader(String name, Matcher<? super List<String>> valueMatcher) {
		return new TypeSafeDiagnosingMatcher<TestToadletContext>() {
			@Override
			protected boolean matchesSafely(TestToadletContext toadletContext, Description mismatchDescription) {
				Map<String, List<String>> headers = toadletContext.getResponseHeaders();
				if (!headers.containsKey(name.toLowerCase())) {
					mismatchDescription.appendText("header ").appendValue(name).appendText(" not present");
					return false;
				}
				if (!valueMatcher.matches(headers.get(name.toLowerCase()))) {
					mismatchDescription.appendText("header ").appendValue(name).appendText(" ");
					valueMatcher.describeMismatch(headers.get(name.toLowerCase()), mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has header ").appendValue(name).appendText(" matching ").appendDescriptionOf(valueMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} that verifies that a
	 * {@link TestToadletContext} has written HTML to its body, then parses
	 * that body and verifies it using the given {@link Document} matcher.
	 *
	 * @param documentMatcher The matcher for the HTML document, if present
	 * @return A matcher for a {@link TestToadletContext}
	 */
	public static Matcher<TestToadletContext> isHtml(Matcher<? super Document> documentMatcher) {
		return new TypeSafeDiagnosingMatcher<TestToadletContext>() {
			@Override
			protected boolean matchesSafely(TestToadletContext toadletContext, Description mismatchDescription) {
				try {
					MimeType mimeType = new MimeType(toadletContext.getResponseHeaders().get("content-type").get(0));
					if (!mimeType.match("text/html")) {
						mismatchDescription.appendText("MIME type was ").appendValue(mimeType);
						return false;
					}
					Document document = Jsoup.parse(toadletContext.getBodyText());
					if (!documentMatcher.matches(document)) {
						mismatchDescription.appendText("Document ");
						documentMatcher.describeMismatch(document, mismatchDescription);
						return false;
					}
					return true;
				} catch (MimeTypeParseException e) {
					throw new RuntimeException(e);
				}
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("is HTML matching ").appendDescriptionOf(documentMatcher);
			}
		};
	}

}
