package freenet.test;

import jakarta.activation.MimeType;
import jakarta.activation.MimeTypeParseException;
import java.net.URI;
import java.util.Map;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

import static freenet.test.LinkMatchers.hasBaseType;
import static freenet.test.LinkMatchers.hasParameter;
import static freenet.test.LinkMatchers.hasQuery;
import static freenet.test.LinkMatchers.hasScheme;
import static freenet.test.LinkMatchers.isKeyValuePairs;
import static freenet.test.LinkMatchers.isMimeType;
import static freenet.test.LinkMatchers.isURI;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

public class LinkMatchersTest {

	@Test
	public void invalidURIsAreRecognized() {
		assertThat(isURI(any(URI.class)).matches("not a URI"), equalTo(false));
	}

	@Test
	public void invalidURIsAreDescribedCorrectly() {
		isURI(any(URI.class)).describeMismatch("not a URI", description);
		assertThat(description.toString(), equalTo("not a URI: \"not a URI\""));
	}

	@Test
	public void isUriMatcherDescribesMismatchOfGivenMatcher() {
		isURI(nullValue()).describeMismatch("/", description);
		assertThat(description.toString(), equalTo("URI was </>"));
	}

	@Test
	public void validURIsAreRecognized() {
		assertThat(isURI(any(URI.class)).matches("scheme://authority/path?query#fragment"), equalTo(true));
	}

	@Test
	public void uriMatcherIsNotConsultedOnInvalidURIs() {
		Matcher<URI> uriMatcher = mock(Matcher.class);
		isURI(uriMatcher).matches("not a URI");
		verifyZeroInteractions(uriMatcher);
	}

	@Test
	public void uriMatcherIsConsultedOnValidURIs() {
		Matcher<URI> uriMatcher = mock(Matcher.class);
		isURI(uriMatcher).matches("scheme://authority/path?query#fragment");
		verify(uriMatcher).matches(ArgumentMatchers.any(URI.class));
	}

	@Test
	public void uriMatcherDescribesItself() {
		isURI(any(URI.class)).describeTo(description);
		assertThat(description.toString(), equalTo("is a URI matching an instance of java.net.URI"));
	}

	@Test
	public void schemeMatcherDoesNotMatchIfThereIsNoScheme() {
		assertThat(hasScheme(any(String.class)).matches(URI.create("./no-scheme")), equalTo(false));
	}

	@Test
	public void schemeMatcherDescribesMismatch() {
		hasScheme(any(String.class)).describeMismatch(URI.create("./no-scheme"), description);
		assertThat(description.toString(), equalTo("no scheme in URI <./no-scheme>"));
	}

	@Test
	public void schemeMatcherDescribeMismatchOfGivenMatcher() {
		hasScheme(nullValue()).describeMismatch(URI.create("http://scheme"), description);
		assertThat(description.toString(), equalTo("scheme was \"http\""));
	}

	@Test
	public void schemeMatcherMatchesIfThereIsAScheme() {
		assertThat(hasScheme(any(String.class)).matches(URI.create("scheme://authority/path?query#fragment")), equalTo(true));
	}

	@Test
	public void schemeMatcherDescribesItself() {
		hasScheme(any(String.class)).describeTo(description);
		assertThat(description.toString(), equalTo("has a scheme matching an instance of java.lang.String"));
	}

	@Test
	public void schemeMatcherIsNotConsultedIfThereIsNoScheme() {
		Matcher<String> schemeMatcher = mock(Matcher.class);
		hasScheme(schemeMatcher).matches(URI.create("./no-scheme"));
		verifyZeroInteractions(schemeMatcher);
	}

	@Test
	public void schemeMatcherIsConsultedIfThereIsAScheme() {
		Matcher<String> schemeMatcher = mock(Matcher.class);
		hasScheme(schemeMatcher).matches(URI.create("scheme://authority/path?query#fragment"));
		verify(schemeMatcher).matches(anyString());
	}

	@Test
	public void queryMatcherDoesNotMatcherIfThereIsNoQuery() {
		assertThat(hasQuery(any(String.class)).matches(URI.create("./no-query")), equalTo(false));
	}

	@Test
	public void queryMatcherDescribesMismatch() {
		hasQuery(any(String.class)).describeMismatch(URI.create("./no-query"), description);
		assertThat(description.toString(), equalTo("no query in URI <./no-query>"));
	}

	@Test
	public void queryMatcherDescribesMismatchOfGivenMatcher() {
		hasQuery(nullValue()).describeMismatch(URI.create("./no?query"), description);
		assertThat(description.toString(), equalTo("query was \"query\""));
	}

	@Test
	public void queryMatcherDoesMatchIfThereIsAQuery() {
		assertThat(hasQuery(any(String.class)).matches(URI.create("./no-query?query")), equalTo(true));
	}

	@Test
	public void queryMatcherDescribesItself() {
		hasQuery(any(String.class)).describeTo(description);
		assertThat(description.toString(), equalTo("has a query matching an instance of java.lang.String"));
	}

	@Test
	public void queryMatcherIsNotConsultedIfThereIsNoQuery() {
		Matcher<String> queryMatcher = mock(Matcher.class);
		hasQuery(queryMatcher).matches(URI.create("./no-query"));
		verifyZeroInteractions(queryMatcher);
	}

	@Test
	public void queryMatcherIsConsultedIfThereIsAQuery() {
		Matcher<String> queryMatcher = mock(Matcher.class);
		hasQuery(queryMatcher).matches(URI.create("./no-query?query"));
		verify(queryMatcher).matches(anyString());
	}

	@Test
	public void keyValuePairsMatcherDoesNotRecognizeEmptyStringAsKeyValuePairs() {
		assertThat(isKeyValuePairs(any(Map.class)).matches(""), equalTo(false));
	}

	@Test
	public void keyValuePairsMatcherDescribesMismatch() {
		isKeyValuePairs(any(Map.class)).describeMismatch("", description);
		assertThat(description.toString(), equalTo("not key-value pairs: \"\""));
	}

	@Test
	public void keyValuePairsMatcherDescribesMismatchOfGivenMatcher() {
		isKeyValuePairs(nullValue()).describeMismatch("foo=bar&baz=quo", description);
		assertThat(description.toString(), equalTo("key-value pairs was <{foo=[bar], baz=[quo]}>"));
	}

	@Test
	public void keyValuePairsMatcherDoesNotRecognizeStringOfWhitespaceOnlyAsKeyValuePairs() {
		assertThat(isKeyValuePairs(any(Map.class)).matches("  \t \r \n "), equalTo(false));
	}

	@Test
	public void keyValuePairsMatcherRecognizesIfThereAreKeyValuePairs() {
		assertThat(isKeyValuePairs(any(Map.class)).matches("foo=bar&baz=quo"), equalTo(true));
	}

	@Test
	public void keyValuePairsMatcherDescribesItself() {
		isKeyValuePairs(any(Map.class)).describeTo(description);
		assertThat(description.toString(), equalTo("is key-value pairs matching an instance of java.util.Map"));
	}

	@Test
	public void keyValuePairsMatcherIsNotConsultedIfThereAreNoKeyValuePairs() {
		Matcher<Map<?, ?>> keyValuePairsMatcher = mock(Matcher.class);
		isKeyValuePairs(keyValuePairsMatcher).matches("");
		verifyZeroInteractions(keyValuePairsMatcher);
	}

	@Test
	public void keyValuePairsMatcherIsConsultedIfThereAreKeyValuePairs() {
		Matcher<Map<?, ?>> keyValuePairsMatcher = mock(Matcher.class);
		isKeyValuePairs(keyValuePairsMatcher).matches("foo=bar&baz=quo");
		verify(keyValuePairsMatcher).matches(ArgumentMatchers.any());
	}

	@Test
	public void mimeTypeMatcherRecognizesIfThereIsNoMimeType() {
		assertThat(isMimeType(any(MimeType.class)).matches("random words"), equalTo(false));
	}

	@Test
	public void mimeTypeMatcherDescribesMismatch() {
		isMimeType(any(MimeType.class)).describeMismatch("random words", description);
		assertThat(description.toString(), equalTo("not a MIME type: \"random words\""));
	}

	@Test
	public void mimeTypeMatcherDescribesMismatchOfGivenMatcher() {
		isMimeType(nullValue()).describeMismatch("x.test/mime-type", description);
		assertThat(description.toString(), equalTo("MIME type was <x.test/mime-type>"));
	}

	@Test
	public void mimeTypeMatcherRecognizesIfThereIsAMimeType() {
		assertThat(isMimeType(any(MimeType.class)).matches("application/x-test"), equalTo(true));
	}

	@Test
	public void mimeTypeMatcherDescribesItself() {
		isMimeType(any(MimeType.class)).describeTo(description);
		assertThat(description.toString(), equalTo("is a MIME type matching an instance of jakarta.activation.MimeType"));
	}

	@Test
	public void mimeTypeMatcherIsNotConsultedIfThereIsNoMimeType() {
		Matcher<MimeType> mimeTypeMatcher = mock(Matcher.class);
		isMimeType(mimeTypeMatcher).matches("random words");
		verifyZeroInteractions(mimeTypeMatcher);
	}

	@Test
	public void mimeTypeMatcherIsConsultedIfThereIsAMimeType() {
		Matcher<MimeType> mimeTypeMatcher = mock(Matcher.class);
		isMimeType(mimeTypeMatcher).matches("application/x-test");
		verify(mimeTypeMatcher).matches(ArgumentMatchers.any());
	}

	@Test
	public void hasBaseTypeMatcherDoesNotMatchADifferentPrimaryType() throws MimeTypeParseException {
		assertThat(hasBaseType("x.test/type").matches(new MimeType("text/plain")), equalTo(false));
	}

	@Test
	public void hasBaseTypeMatcherDoesNotMatchADifferentSubType() throws MimeTypeParseException {
		assertThat(hasBaseType("x.test/type").matches(new MimeType("x.test/different")), equalTo(false));
	}

	@Test
	public void hasBaseTypeMatcherDescribesMismatch() throws MimeTypeParseException {
		hasBaseType("x.test/type").describeMismatch(new MimeType("text/plain"), description);
		assertThat(description.toString(), equalTo("was <text/plain>"));
	}

	@Test
	public void hasBaseTypeMatcherMatchesIfBaseTypeMatches() throws MimeTypeParseException {
		assertThat(hasBaseType("x.test/type").matches(new MimeType("x.test/type")), equalTo(true));
	}

	@Test
	public void hasBaseTypeMatcherMatchesIgnoringParameters() throws MimeTypeParseException {
		assertThat(hasBaseType("x.test/type").matches(new MimeType("x.test/type; foo=bar")), equalTo(true));
	}

	@Test
	public void hasBaseTypeMatcherDescribesItself() {
		hasBaseType("x.test/type").describeTo(description);
		assertThat(description.toString(), equalTo("has base type \"x.test/type\""));
	}

	@Test
	public void hasParameterMatcherRecognizesIfParameterDoesNotExist() throws MimeTypeParseException {
		assertThat(hasParameter("foo", any(String.class)).matches(new MimeType("x.test/mime-type")), equalTo(false));
	}

	@Test
	public void hasParameterMatcherDescribesMismatchCorrectly() throws MimeTypeParseException {
		hasParameter("foo", any(String.class)).describeMismatch(new MimeType("x.test/mime-type"), description);
		assertThat(description.toString(), equalTo("parameter not found: \"foo\""));
	}

	@Test
	public void hasParameterMatcherDescribesMismatchOfGivenMatcher() throws MimeTypeParseException {
		hasParameter("foo", nullValue()).describeMismatch(new MimeType("x.test/mime-type; foo=bar"), description);
		assertThat(description.toString(), equalTo("parameter \"foo\" was \"bar\""));
	}

	@Test
	public void hasParameterMatcherRecognizesIfParameterDoesExist() throws MimeTypeParseException {
		assertThat(hasParameter("foo", any(String.class)).matches(new MimeType("x.test/mime-type; foo=bar")), equalTo(true));
	}

	@Test
	public void hasParameterMatcherDescribesItself() {
		hasParameter("foo", any(String.class)).describeTo(description);
		assertThat(description.toString(), equalTo("has parameter \"foo\" matching an instance of java.lang.String"));
	}

	@Test
	public void valueMatcherIsNotConsultedIfParameterDoesNotExist() throws MimeTypeParseException {
		Matcher<String> valueMatcher = mock(Matcher.class);
		hasParameter("foo", valueMatcher).matches(new MimeType("x.test/mime-type"));
		verifyZeroInteractions(valueMatcher);
	}

	@Test
	public void valueMatcherIsConsultedIfParameterExists() throws MimeTypeParseException {
		Matcher<String> valueMatcher = mock(Matcher.class);
		hasParameter("foo", valueMatcher).matches(new MimeType("x.test/mime-type; foo=bar"));
		verify(valueMatcher).matches(ArgumentMatchers.any());
	}

	private final Description description = new StringDescription();

}
