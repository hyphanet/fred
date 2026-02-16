package freenet.test;

import java.nio.charset.StandardCharsets;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.equalTo;

/**
 * Hamcrest {@link Matcher}s that can verify properties of
 * {@link HttpResponse}s.
 */
public class HttpResponseMatchers {

	/**
	 * Returns a matcher that matches an {@link HttpResponse} with the
	 * given status code.
	 *
	 * @param status The status code to match
	 * @return A matcher for {@link HttpResponse}s
	 */
	public static Matcher<HttpResponse> hasStatus(int status) {
		return hasStatus(equalTo(status));
	}

	/**
	 * Returns a matcher that matches an {@link HttpResponse} with the
	 * given status code.
	 *
	 * @param statusMatcher The matcher for the status code
	 * @return A matcher for {@link HttpResponse}s
	 */
	public static Matcher<HttpResponse> hasStatus(Matcher<? super Integer> statusMatcher) {
		return hasStatus(statusMatcher, anything());
	}

	/**
	 * Returns a matcher that matches an {@link HttpResponse} with the
	 * given status code and status text.
	 *
	 * @param statusMatcher The matcher for the status code
	 * @param statusTextMatcher The matcher for the status text
	 * @return A matcher for {@link HttpResponse}s
	 */
	public static Matcher<HttpResponse> hasStatus(Matcher<? super Integer> statusMatcher, Matcher<? super String> statusTextMatcher) {
		return new TypeSafeDiagnosingMatcher<HttpResponse>() {
			@Override
			protected boolean matchesSafely(HttpResponse httpResponse, Description mismatchDescription) {
				if (!statusMatcher.matches(httpResponse.getStatusCode())) {
					mismatchDescription.appendText("status was ").appendValue(httpResponse.getStatusCode());
					return false;
				}
				if (!statusTextMatcher.matches(httpResponse.getStatusText())) {
					mismatchDescription.appendText("status text was ").appendValue(httpResponse.getStatusText());
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("status is ").appendDescriptionOf(statusMatcher).appendText(" and status text is ").appendDescriptionOf(statusTextMatcher);
			}
		};
	}

	/**
	 * Returns a matcher that matches an {@link HttpResponse} that has the
	 * wanted header.
	 *
	 * @param name The name of the header
	 * @param valuesMatcher The matcher for the values
	 * @return A matcher for {@link HttpResponse}s
	 */
	public static Matcher<HttpResponse> hasHeader(String name, Matcher<? super Iterable<String>> valuesMatcher) {
		return new TypeSafeDiagnosingMatcher<HttpResponse>() {
			@Override
			protected boolean matchesSafely(HttpResponse httpResponse, Description mismatchDescription) {
				if (!valuesMatcher.matches(httpResponse.getHeaders(name))) {
					mismatchDescription.appendText("header ").appendValue(name).appendText(" was ").appendValue(httpResponse.getHeaders(name));
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("header ").appendValue(name).appendText(" matches ").appendDescriptionOf(valuesMatcher);
			}
		};
	}

	/**
	 * Returns a matcher that matches an {@link HttpResponse} that has the
	 * wanted headers. This matcher only matches the names of the headers,
	 * for verifying values, see {@link #hasHeader(String, Matcher)}.
	 *
	 * <p><em>
	 * Header names are stored case-insensitively, so matching must be done
	 * the same way!
	 * </em></p>
	 *
	 * <pre>
	 * assertThat(httpResponse, hasHeaders(containsInAnyOrder(
	 *     equalToIgnoringCase("content-type"),
	 *     equalToIgnoringCase("content-length")
	 * )));
	 * </pre>
	 *
	 * @param headersMatcher The headers to match
	 * @return A matcher for {@link HttpResponse}s
	 */
	public static Matcher<HttpResponse> hasHeaders(Matcher<? super Iterable<String>> headersMatcher) {
		return new TypeSafeDiagnosingMatcher<HttpResponse>() {
			@Override
			protected boolean matchesSafely(HttpResponse item, Description mismatchDescription) {
				if (!headersMatcher.matches(item.getHeaders())) {
					mismatchDescription.appendText("headers were ").appendValue(item.getHeaders());
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("headers match ").appendDescriptionOf(headersMatcher);
			}
		};
	}

	/**
	 * Returns a matcher that matches {@link HttpResponse}s whose body is
	 * matched by the given matcher.
	 *
	 * @param bodyMatcher The matcher for the body’s byte array
	 * @return A matcher for {@link HttpResponse}s
	 */
	public static Matcher<HttpResponse> hasBody(Matcher<? super byte[]> bodyMatcher) {
		return new TypeSafeDiagnosingMatcher<HttpResponse>() {
			@Override
			protected boolean matchesSafely(HttpResponse httpResponse, Description mismatchDescription) {
				if (!httpResponse.getBody().isPresent()) {
					mismatchDescription.appendText("body was missing");
					return false;
				}
				if (!bodyMatcher.matches(httpResponse.getBody().get())) {
					mismatchDescription.appendText("body was ").appendValue(httpResponse.getBody().get());
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("body is ").appendDescriptionOf(bodyMatcher);
			}
		};
	}

	/**
	 * Returns a matcher that matches {@link HttpResponse}s whose body when
	 * converted to a {@link String} using the {@link StandardCharsets#UTF_8
	 * UTF-8 charset} is matched by the given matcher.
	 *
	 * @param bodyMatcher The matcher for the body
	 * @return A matcher for {@link HttpResponse}s
	 */
	public static Matcher<HttpResponse> hasStringBody(Matcher<? super String> bodyMatcher) {
		return new TypeSafeDiagnosingMatcher<HttpResponse>() {
			@Override
			protected boolean matchesSafely(HttpResponse httpResponse, Description mismatchDescription) {
				if (!httpResponse.getBody().isPresent()) {
					mismatchDescription.appendText("body was missing");
					return false;
				}
				String bodyAsString = new String(httpResponse.getBody().get(), UTF_8);
				if (!bodyMatcher.matches(bodyAsString)) {
					mismatchDescription.appendText("body ");
					bodyMatcher.describeMismatch(bodyAsString, mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("body matches ").appendDescriptionOf(bodyMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} for {@link HttpResponse}s that do not have
	 * a body.
	 *
	 * @return A matcher for {@link HttpResponse}s
	 */
	public static Matcher<HttpResponse> hasNoBody() {
		return new TypeSafeDiagnosingMatcher<HttpResponse>() {
			@Override
			protected boolean matchesSafely(HttpResponse httpResponse, Description mismatchDescription) {
				if (httpResponse.getBody().isPresent()) {
					mismatchDescription.appendText("body was ").appendValue(httpResponse.getBody().get());
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has no body");
			}
		};
	}

}
