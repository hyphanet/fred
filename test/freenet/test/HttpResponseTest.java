package freenet.test;

import java.util.List;
import org.junit.Test;

import static freenet.test.HttpResponseMatchers.hasBody;
import static freenet.test.HttpResponseMatchers.hasHeader;
import static freenet.test.HttpResponseMatchers.hasHeaders;
import static freenet.test.HttpResponseMatchers.hasNoBody;
import static freenet.test.HttpResponseMatchers.hasStatus;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;

public class HttpResponseTest {

	@Test
	public void singleSimpleHttpResponseCanBeParsed() {
		List<HttpResponse> httpResponses = HttpResponse.parse("HTTP/1.0 200 OK\r\nHeader: Yes\r\n");
		assertThat(httpResponses, contains(allOf(
				hasStatus(equalTo(200), equalTo("OK")),
				hasHeaders(contains(equalToIgnoringCase("Header"))),
				hasHeader("Header", contains("Yes"))
		)));
	}

	@Test
	public void statusTextWithMultipleWordsIsParsedCorrectly() {
		List<HttpResponse> httpResponses = HttpResponse.parse("HTTP/1.0 200 Everything is under control\r\nHeader: Yes\r\n");
		assertThat(httpResponses, contains(hasStatus(anything(), equalTo("Everything is under control"))));
	}

	@Test
	public void httpResponseMatchesHeadersCaseInsensitively() {
		List<HttpResponse> httpResponses = HttpResponse.parse("HTTP/1.0 200 OK\r\nHeader: Yes\r\n");
		assertThat(httpResponses, contains(hasHeader("hEaDeR", contains("Yes"))));
	}

	@Test
	public void httpResponseWithMultipleHeadersIsParsedCorrectly() {
		List<HttpResponse> httpResponses = HttpResponse.parse("HTTP/1.0 200 OK\r\nHeader: Yes\r\nOther-Header: No\r\nHeader: maybe\r\n");
		assertThat(httpResponses, contains(allOf(
				hasStatus(equalTo(200)),
				hasHeaders(containsInAnyOrder("header", "other-header")),
				hasHeader("Header", contains("Yes", "maybe")),
				hasHeader("Other-Header", contains("No"))
		)));
	}

	@Test
	public void httpResponseBodyCanBeParsedCorrectly() {
		List<HttpResponse> httpResponses = HttpResponse.parse("HTTP/1.0 200 OK\r\nContent-Length: 5\r\n\r\nHello");
		assertThat(httpResponses, contains(hasBody(equalTo("Hello".getBytes(UTF_8)))));
	}

	@Test
	public void httpResponseBodyCanNotBeParsedWithoutContentLength() {
		List<HttpResponse> httpResponses = HttpResponse.parse("HTTP/1.0 200 OK\r\n\r\nHTTP/1.0 200 Hello");
		assertThat(httpResponses, contains(
				hasNoBody(),
				hasNoBody()
		));
	}

	@Test
	public void multipleHttpResponsesCanBeParsed() {
		List<HttpResponse> httpResponses = HttpResponse.parse("HTTP/1.0 200 OK\r\n\r\nHTTP/1.0 301 More to Come\r\nContent-Length: 2\r\n\r\nOK");
		assertThat(httpResponses, contains(
				allOf(hasStatus(200), hasNoBody()),
				allOf(hasStatus(301), hasHeader("Content-Length", contains("2")), hasBody(equalTo("OK".getBytes(UTF_8))))
		));
	}

}
