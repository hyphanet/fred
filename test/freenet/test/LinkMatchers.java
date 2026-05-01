package freenet.test;

import freenet.clients.http.HTTPRequestImpl;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

/**
 * This class contains Hamcrest {@link Matcher}s that can be used to verify
 * links in HTML documents. The links need to be HTML-decoded (e.g. by
 * using Jsoup or any other actual HTML parser) before these matchers can
 * be applied.
 *
 * <h2>Usage</h2>
 * <p>
 * The matchers in this class are designed to be used in a “drill-down”
 * manner; first, a {@link String} is verified as and converted into a URI
 * by {@link #isURI(Matcher)}, then matchers for the URI can verify the
 * parts of a URI (such as the scheme, or the query), then the parts are
 * verified and again converted into appropriate subtypes (e.g. query
 * parameters are parsed into a Map&lt;String, List&lt;String>>), and
 * these values can then again be verified and converted into other
 * subtypes, such as {@link MimeType}s.
 * </p>
 * <p>
 * To verify that a link is a valid URI:
 * </p>
 * <pre>
 * assertThat(link, isURI(any(URI.class)));
 * </pre>
 * <p>
 * To verify that a link is an HTTPS link:
 * </p>
 * <pre>
 * assertThat(link, isURI(hasScheme(equalTo("https"))));
 * </pre>
 * <p>
 * Generally, the {@code is*} matchers take a {@link String} and verify
 * that it conforms to some specification, while the {@code has*} matchers
 * then verify object-specific properties on an instance of the actual
 * object; e.g. the {@link #isMimeType(Matcher)} matcher verifies that
 * a {@link String} actually represents a MIME type, before the
 * {@link #hasBaseType(String)} matcher then operates on a
 * {@link MimeType} instance for further verification.
 * </p>
 * <p>
 * The matchers can be combined like any other {@link Matcher}, too. This
 * would check for a link that has either an HTTP or an HTTPS scheme,
 * and a query part that begins with the {@link String} {@code "foo"}.
 * </p>
 * <pre>
 * assertThat(link, isURI(allOf(
 * 	anyOf(
 * 		hasScheme(equalTo("http")),
 * 		hasScheme(equalTo("https"))
 * 	),
 * 	hasQuery(startsWith("foo"))
 * )));
 * </pre>
 */
public class LinkMatchers {

	/**
	 * Returns a {@link Matcher} that can verify that a {@link String}
	 * represents a valid URI, and that the URI conforms to the given
	 * secondary {@link Matcher}.
	 *
	 * @param uriMatcher The matcher for the URI
	 * @return A matcher matching URIs
	 */
	public static Matcher<String> isURI(Matcher<? super URI> uriMatcher) {
		return new TypeSafeDiagnosingMatcher<String>() {
			@Override
			protected boolean matchesSafely(String uriString, Description mismatchDescription) {
				try {
					URI uri = new URI(uriString);
					if (!uriMatcher.matches(uri)) {
						mismatchDescription.appendText("URI ");
						uriMatcher.describeMismatch(uri, mismatchDescription);
						return false;
					}
					return true;
				} catch (URISyntaxException e) {
					mismatchDescription.appendText("not a URI: ").appendValue(uriString);
					return false;
				}
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("is a URI matching ").appendDescriptionOf(uriMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} that verifies the presence of a
	 * {@link URI#getScheme()}, and that is matches the given
	 * {@link Matcher}.
	 *
	 * @param schemeMatcher The matcher for the scheme, if present
	 * @return A matcher for a URI
	 */
	public static Matcher<URI> hasScheme(Matcher<? super String> schemeMatcher) {
		return new TypeSafeDiagnosingMatcher<URI>() {
			@Override
			protected boolean matchesSafely(URI uri, Description mismatchDescription) {
				if (uri.getScheme() == null) {
					mismatchDescription.appendText("no scheme in URI ").appendValue(uri);
					return false;
				}
				if (!schemeMatcher.matches(uri.getScheme())) {
					mismatchDescription.appendText("scheme ");
					schemeMatcher.describeMismatch(uri.getScheme(), mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has a scheme matching ").appendDescriptionOf(schemeMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} that verifies the presence of a
	 * {@link URI#getQuery()}, and that it matches the given
	 * {@link Matcher}.
	 *
	 * @param queryMatcher The matcher for the query, if present
	 * @return A matcher for a URI
	 */
	public static Matcher<URI> hasQuery(Matcher<? super String> queryMatcher) {
		return new TypeSafeDiagnosingMatcher<URI>() {
			@Override
			protected boolean matchesSafely(URI uri, Description mismatchDescription) {
				if (uri.getQuery() == null) {
					mismatchDescription.appendText("no query in URI ").appendValue(uri);
					return false;
				}
				if (!queryMatcher.matches(uri.getQuery())) {
					mismatchDescription.appendText("query ");
					queryMatcher.describeMismatch(uri.getQuery(), mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has a query matching ").appendDescriptionOf(queryMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} that verifies the presence of key-value
	 * pairs, and that they match the given {@link Matcher}.
	 * <p>
	 * An empty {@link String}, or a {@link String} consisting solely of
	 * whitespace (according to {@link String#trim()}) is not recognized as
	 * key-value pairs.
	 * </p>
	 *
	 * @param keyValuePairsMatcher The matcher for the key-value pairs,
	 * 		if present
	 * @return A matcher for a {@link String} containing key-value pairs
	 */
	public static Matcher<String> isKeyValuePairs(Matcher<? super Map<? extends String, ? extends Iterable<String>>> keyValuePairsMatcher) {
		return new TypeSafeDiagnosingMatcher<String>() {
			@Override
			protected boolean matchesSafely(String keyValuePairsString, Description mismatchDescription) {
				if (keyValuePairsString.trim().isEmpty()) {
					mismatchDescription.appendText("not key-value pairs: ").appendValue(keyValuePairsString);
					return false;
				}
				Map<String, List<String>> uriParameters = HTTPRequestImpl.parseUriParameters(keyValuePairsString, false);
				if (!keyValuePairsMatcher.matches(uriParameters)) {
					mismatchDescription.appendText("key-value pairs ");
					keyValuePairsMatcher.describeMismatch(uriParameters, mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("is key-value pairs matching ").appendDescriptionOf(keyValuePairsMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} that verifies the presence of a MIME type,
	 * and that it matches the given {@link Matcher}.
	 *
	 * @param mimeTypeMatcher The matcher for the MIME type, if present
	 * @return A matcher for a {@link String} containing a MIME type
	 */
	public static Matcher<String> isMimeType(Matcher<? super MimeType> mimeTypeMatcher) {
		return new TypeSafeDiagnosingMatcher<String>() {
			@Override
			protected boolean matchesSafely(String mimeTypeString, Description mismatchDescription) {
				try {
					MimeType mimeType = new MimeType(mimeTypeString);
					if (!mimeTypeMatcher.matches(mimeType)) {
						mismatchDescription.appendText("MIME type ");
						mimeTypeMatcher.describeMismatch(mimeType, mismatchDescription);
						return false;
					}
					return true;
				} catch (MimeTypeParseException e) {
					mismatchDescription.appendText("not a MIME type: ").appendValue(mimeTypeString);
					return false;
				}
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("is a MIME type matching ").appendDescriptionOf(mimeTypeMatcher);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} for a MIME type’s base type. The base type
	 * comprises the primary and the sub type, ignoring all parameters.
	 *
	 * @param baseType The base type to match
	 * @return A matcher for a MIME type
	 */
	public static Matcher<MimeType> hasBaseType(String baseType) {
		return new TypeSafeDiagnosingMatcher<MimeType>() {
			@Override
			protected boolean matchesSafely(MimeType mimeType, Description mismatchDescription) {
				try {
					if (!mimeType.match(baseType)) {
						mismatchDescription.appendText("was ").appendValue(mimeType);
						return false;
					}
				} catch (MimeTypeParseException e) {
					throw new RuntimeException(e);
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has base type ").appendValue(baseType);
			}
		};
	}

	/**
	 * Returns a {@link Matcher} for a parameter of a MIME type.
	 *
	 * @param name The name of the parameter
	 * @param valueMatcher The matcher for the value of the parameter
	 * @return A matcher for a MIME type
	 */
	public static Matcher<MimeType> hasParameter(String name, Matcher<? super String> valueMatcher) {
		return new TypeSafeDiagnosingMatcher<MimeType>() {
			@Override
			protected boolean matchesSafely(MimeType mimeType, Description mismatchDescription) {
				if (mimeType.getParameter(name) == null) {
					mismatchDescription.appendText("parameter not found: ").appendValue(name);
					return false;
				}
				if (!valueMatcher.matches(mimeType.getParameter(name))) {
					mismatchDescription.appendText("parameter ").appendValue(name).appendText(" ");
					valueMatcher.describeMismatch(mimeType.getParameter(name), mismatchDescription);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("has parameter ").appendValue(name).appendText(" matching ").appendDescriptionOf(valueMatcher);
			}
		};
	}

}
