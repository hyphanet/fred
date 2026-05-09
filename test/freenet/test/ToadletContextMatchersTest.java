package freenet.test;

import freenet.clients.http.TestToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.MultiValueTable;
import jakarta.activation.MimeType;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;
import org.jsoup.nodes.Document;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import static freenet.test.ToadletContextMatchers.hasBodyText;
import static freenet.test.ToadletContextMatchers.hasContentType;
import static freenet.test.ToadletContextMatchers.hasHeader;
import static freenet.test.ToadletContextMatchers.hasStatus;
import static freenet.test.ToadletContextMatchers.isHtml;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

public class ToadletContextMatchersTest {

	@Test
	public void hasStatusMatcherRecognizesThatStatusHasNotBeenSet() {
		assertThat(hasStatus(any(Integer.class)).matches(toadletContext), equalTo(false));
	}

	@Test
	public void hasStatusMatcherDescribesMismatchCorrectly() {
		hasStatus(any(Integer.class)).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("no status set"));
	}

	@Test
	public void hasStatusMatcherDescribesMismatchOfGivenMatcher() throws ToadletContextClosedException, IOException {
		toadletContext.sendReplyHeadersStatic(404, "Not Found", null, null, 0, new Date());
		hasStatus(equalTo(200)).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("status code was <404>"));
	}

	@Test
	public void hasStatusMatcherRecognizesThatStatusHasBeenSet() throws ToadletContextClosedException, IOException {
		toadletContext.sendReplyHeadersStatic(200, "OK", null, null, 0, new Date());
		assertThat(hasStatus(any(Integer.class)).matches(toadletContext), equalTo(true));
	}

	@Test
	public void hasStatusMatcherDescribesItself() {
		hasStatus(any(Integer.class)).describeTo(description);
		assertThat(description.toString(), equalTo("has status matching an instance of java.lang.Integer"));
	}

	@Test
	public void statusMatcherIsNotConsultedIfStatusHasNotBeenSet() {
		hasStatus(statusMatcher).matches(toadletContext);
		verifyZeroInteractions(statusMatcher);
	}

	@Test
	public void statusMatcherIsConsultedIfStatusHasBeenSet() throws ToadletContextClosedException, IOException {
		toadletContext.sendReplyHeadersStatic(301, "OK", null, null, 0, new Date());
		hasStatus(statusMatcher).matches(toadletContext);
		verify(statusMatcher).matches(eq(301));
	}

	@Test
	public void hasContentTypeMatcherDoesNotMatchIfNoContentTypeHeaderIsPresent() {
		assertThat(hasContentType(any(MimeType.class)).matches(toadletContext), equalTo(false));
	}

	@Test
	public void hasContentTypeMatcherDescribesMismatch() {
		hasContentType(any(MimeType.class)).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("no content type set"));
	}

	@Test
	public void hasContentTypeMatcherDescribesMismatchOfMimeTypeMatcher() throws Exception {
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/html", 0, null);
		hasContentType(nullValue()).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("content type was <text/html>"));
	}

	@Test
	public void hasContentTypeMatcherMatchesIfContentTypeIsPresent() throws Exception {
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/html", 0, null);
		assertThat(hasContentType(any(MimeType.class)).matches(toadletContext), equalTo(true));
	}

	@Test
	public void hasContentTypeMatcherDescribesItself() {
		hasContentType(any(MimeType.class)).describeTo(description);
		assertThat(description.toString(), equalTo("has content type matching an instance of jakarta.activation.MimeType"));
	}

	@Test
	public void mimeTypeMatcherIsNotConsultedIfThereIsNoContentType() {
		hasContentType(mimeTypeMatcher).matches(toadletContext);
		verifyZeroInteractions(mimeTypeMatcher);
	}

	@Test
	public void mimeTypeMatcherIsConsultedIfThereIsAContentType() throws Exception {
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/html", 0, null);
		hasContentType(mimeTypeMatcher).matches(toadletContext);
		ArgumentCaptor<MimeType> mimeTypeCaptor = ArgumentCaptor.forClass(MimeType.class);
		verify(mimeTypeMatcher).matches(mimeTypeCaptor.capture());
		assertThat(mimeTypeCaptor.getValue().getBaseType(), equalTo("text/html"));
		assertThat(mimeTypeCaptor.getValue().getParameters().isEmpty(), equalTo(true));
	}

	@Test
	public void hasBodyTextMatcherDoesNotMatchIfBodyHasNotBeenWritten() {
		assertThat(hasBodyText(any(String.class)).matches(toadletContext), equalTo(false));
	}

	@Test
	public void hasBodyTextMatcherDescribesMismatch() {
		hasBodyText(any(String.class)).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("no body text present"));
	}

	@Test
	public void hasBodyTextMatcherDescribesMismatchOfGivenMatcher() throws ToadletContextClosedException, IOException {
		toadletContext.writeData("test".getBytes(UTF_8));
		hasBodyText(startsWith("foo")).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("body text was \"test\""));
	}

	@Test
	public void hasBodyTextMatcherMatchesIfBodyIsPresent() throws ToadletContextClosedException, IOException {
		toadletContext.writeData("test".getBytes(UTF_8));
		assertThat(hasBodyText(any(String.class)).matches(toadletContext), equalTo(true));
	}

	@Test
	public void hasBodyTextMatcherDescribesItselfCorrectly() {
		hasBodyText(any(String.class)).describeTo(description);
		assertThat(description.toString(), equalTo("has text body matching an instance of java.lang.String"));
	}

	@Test
	public void bodyTextMatcherIsNotConsultedIfBodyIsNotPresent() {
		hasBodyText(bodyTextMatcher).matches(toadletContext);
		verifyZeroInteractions(bodyTextMatcher);
	}

	@Test
	public void bodyTextMatcherIsConsultedIfBodyIsPresent() throws ToadletContextClosedException, IOException {
		toadletContext.writeData("test".getBytes(UTF_8));
		hasBodyText(bodyTextMatcher).matches(toadletContext);
		verify(bodyTextMatcher).matches(eq("test"));
	}

	@Test
	public void hasHeaderDoesNotMatchIfHeaderDoesNotExist() {
		assertThat(hasHeader("foo", any(List.class)).matches(toadletContext), equalTo(false));
	}

	@Test
	public void hasHeaderDescribesMismatch() {
		hasHeader("foo", any(List.class)).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("header \"foo\" not present"));
	}

	@Test
	public void hasHeaderDescribesMismatchOfGivenMatcher() throws ToadletContextClosedException, IOException {
		MultiValueTable<String, String> headers = new MultiValueTable<>();
		headers.put("foo", "bar");
		toadletContext.sendReplyHeadersStatic(200, "OK", headers, null, 0, null);
		hasHeader("foo", nullValue()).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("header \"foo\" was <[bar]>"));
	}

	@Test
	public void hasHeaderMatchesIfHeaderExists() throws ToadletContextClosedException, IOException {
		MultiValueTable<String, String> headers = new MultiValueTable<>();
		headers.put("foo", "bar");
		toadletContext.sendReplyHeadersStatic(200, "OK", headers, null, 0, null);
		assertThat(hasHeader("foo", any(List.class)).matches(toadletContext), equalTo(true));
	}

	@Test
	public void hasHeaderDescribeItself() {
		hasHeader("foo", any(List.class)).describeTo(description);
		assertThat(description.toString(), equalTo("has header \"foo\" matching an instance of java.util.List"));
	}

	@Test
	public void valueMatcherIsNotConsultedIfHeaderDoesNotExist() {
		hasHeader("foo", headerMatcher).matches(toadletContext);
		verifyZeroInteractions(headerMatcher);
	}

	@Test
	public void valueMatcherIsConsultedIfHeaderExists() throws ToadletContextClosedException, IOException {
		MultiValueTable<String, String> headers = new MultiValueTable<>();
		headers.put("foo", "bar");
		toadletContext.sendReplyHeadersStatic(200, "OK", headers, null, 0, null);
		hasHeader("foo", headerMatcher).matches(toadletContext);
		verify(headerMatcher).matches(eq(asList("bar")));
	}

	@Test
	public void isHtmlMatcherDoesNotMatchIfContentTypeIsNotHtml() throws Exception {
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/plain", 0, null);
		assertThat(isHtml(any(Document.class)).matches(toadletContext), equalTo(false));
	}

	@Test
	public void isHtmlMatcherDescribesMismatch() throws Exception {
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/plain", 0, null);
		isHtml(any(Document.class)).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("MIME type was <text/plain>"));
	}

	@Test
	public void isHtmlMatcherMatchesIfContentTypeIsHtml() throws Exception {
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/html; charset=utf-8", 0, null);
		toadletContext.writeData("test".getBytes(UTF_8));
		assertThat(isHtml(any(Document.class)).matches(toadletContext), equalTo(true));
	}

	@Test
	public void isHtmlMatcherDescribesItself() {
		isHtml(any(Document.class)).describeTo(description);
		assertThat(description.toString(), equalTo("is HTML matching an instance of org.jsoup.nodes.Document"));
	}

	@Test
	public void documentMatcherIsNotConsultedIfMimeTypeIsNotHtml() throws Exception {
		Matcher<Document> documentMatcher = mock(Matcher.class);
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/plain", 0, null);
		isHtml(documentMatcher).matches(toadletContext);
		verifyZeroInteractions(documentMatcher);
	}

	@Test
	public void isHtmlMatcherDescribesMismatchOfDocumentMatcher() throws ToadletContextClosedException, IOException {
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/html; charset=utf-8", 0, null);
		toadletContext.writeData("test".getBytes(UTF_8));
		isHtml(nullValue()).describeMismatch(toadletContext, description);
		assertThat(description.toString(), equalTo("Document was <<html>\n <head></head>\n <body>test</body>\n</html>>"));
	}

	@Test
	public void documentMatcherIsConsultedIfMimeTypeIsHtml() throws Exception {
		Matcher<Document> documentMatcher = mock(Matcher.class);
		toadletContext.sendReplyHeadersStatic(200, "OK", null, "text/html; charset=utf-8", 0, null);
		toadletContext.writeData("test".getBytes(UTF_8));
		isHtml(documentMatcher).matches(toadletContext);
		verify(documentMatcher).matches(ArgumentMatchers.any(Document.class));
	}

	private final TestToadletContext toadletContext = TestToadletContext.builder().build();
	private final Description description = new StringDescription();
	private final Matcher<Integer> statusMatcher = mock(Matcher.class);
	private final Matcher<MimeType> mimeTypeMatcher = mock(Matcher.class);
	private final Matcher<String> bodyTextMatcher = mock(Matcher.class);
	private final Matcher<List<String>> headerMatcher = mock(Matcher.class);

}
