package freenet.clients.http;

import freenet.support.io.ArrayBucketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Arrays;
import org.junit.Test;

import static freenet.test.HttpResponse.parse;
import static freenet.test.HttpResponseMatchers.hasBody;
import static freenet.test.HttpResponseMatchers.hasHeader;
import static freenet.test.HttpResponseMatchers.hasStatus;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ToadletContextImplTest {

	@Test
	public void emptyRequestWillCloseTheSocket() throws IOException {
		setupInputStream("");
		ToadletContextImpl.handle(socket, null, null, null, null);
		verify(socket).close();
	}

	@Test
	public void requestingHomepageWillReturnHomepage() throws Exception {
		setupInputStream("GET / HTTP/1.0\r\n\r\n");
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(allOf(
				hasStatus(200), hasBody(equalTo("OK\n".getBytes(UTF_8)))
		)));
	}

	@Test
	public void requestingNoOutputPageWillReturnStatus204() throws Exception {
		setupInputStream("GET / HTTP/1.0\r\n\r\n");
		when(toadletContainer.findToadlet(any())).thenReturn(noOutputToadlet);
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(204), equalTo("No Content"))));
	}

	@Test
	public void redirectExceptionFromToadletResultsInRedirect() throws Exception {
		setupInputStream("GET /redirect-toadlet HTTP/1.0\r\n\r\n");
		Toadlet redirectingToadlet = mock(Toadlet.class, RETURNS_DEEP_STUBS);
		when(redirectingToadlet.findSupportedMethods()).thenReturn("GET");
		doThrow(new RedirectException("/new-location")).when(redirectingToadlet).handleMethodGET(any(), any(), any());
		when(toadletContainer.findToadlet(new URI("/redirect-toadlet"))).thenReturn(redirectingToadlet);
		when(toadletContainer.findToadlet(new URI("/new-location"))).thenReturn(homepageToadlet);
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(allOf(hasStatus(200))));
	}

	@Test
	public void sendingInvalidHttpRequestLineResultsInHttpStatus400() throws IOException {
		setupInputStream("GET HTTP/1.0\r\n\r\n");
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
	}

	@Test
	public void sendingInvalidHttpVersionResultsInHttpStatus400() throws IOException {
		setupInputStream("GET / HTTP/123.0\r\n\r\n");
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
	}

	@Test
	public void sendingInvalidUrlResultsInHttpStatus400() throws IOException {
		setupInputStream("GET :invalid HTTP/1.0\r\n\r\n");
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
	}

	@Test
	public void getRequestWithExtremelyLongUrlResultsInHttpStatus400() throws IOException {
		setupInputStream("GET /extremely-long-url-" + generateLongString() + " HTTP/1.0\r\nContent-Length: 0\r\n\r\n");
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
	}

	private String generateLongString() {
		char[] longString = new char[33000];
		Arrays.fill(longString, 'a');
		return new String(longString);
	}

	@Test
	public void sendingInvalidHeaderResultsInHttpStatus400() throws IOException {
		setupInputStream("GET /invalid-header HTTP/1.0\r\nInvalid-Header Yes\r\n\r\n");
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
	}

	@Test
	public void endingTheRequestDuringTheHeadersWillCloseTheSocket() throws Exception {
		setupInputStream("GET / HTTP/1.0\r\nHeader: Yes");
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		verify(socket).close();
	}

	@Test
	public void emptyLinesBeforeTheFirstLineAreIgnored() throws Exception {
		setupInputStream("\r\n\r\n\r\nGET / HTTP/1.0\r\n\r\n");
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(allOf(
				hasStatus(equalTo(200), equalTo("OK")),
				hasBody(equalTo("OK\n".getBytes(UTF_8)))
		)));
	}

	@Test
	public void sendingGetRequestWithContentLengthHeaderResultsInHttpStatus400() throws IOException {
		setupInputStream("GET /too-much-content-length HTTP/1.0\r\nContent-Length: 1\r\n\r\n");
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
	}

	@Test
	public void getRequestWithoutToadletResultsInHttpStatus404() throws Exception {
		setupInputStream("GET /no-toadlet HTTP/1.1\r\n\r\n");
		when(toadletContainer.findToadlet(any())).thenReturn(null);
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(404), equalTo("Not Found"))));
	}

	@Test
	public void permanentRedirectExceptionWhenLocatingToadletWillResultInRedirect() throws Exception {
		setupInputStream("GET /redirect-from-container HTTP/1.0\r\n\r\n");
		when(toadletContainer.findToadlet(any())).thenThrow(new PermanentRedirectException(new URI("/new-location")));
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(allOf(
				hasStatus(equalTo(301), equalTo("Moved Permanently")),
				hasHeader("Location", contains("/new-location"))
		)));
	}

	@Test
	public void requestWithFullAccessForwardsRequestToPageMakerForParsingAdvancedModeSwitches() throws Exception {
		setupInputStream("GET / HTTP/1.0\r\n\r\n");
		when(toadletContainer.isAllowedFullAccess(any())).thenReturn(true);
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		ToadletContextImpl.handle(socket, toadletContainer, pageMaker, null, null);
		verify(pageMaker).parseMode(any(), eq(toadletContainer));
	}

	@Test
	public void requestWithoutFullAccessDoesNotForwardRequestToPageMakerForParsingAdvancedModeSwitches() throws Exception {
		setupInputStream("GET / HTTP/1.0\r\n\r\n");
		when(toadletContainer.isAllowedFullAccess(any())).thenReturn(false);
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		ToadletContextImpl.handle(socket, toadletContainer, pageMaker, null, null);
		verify(pageMaker, never()).parseMode(any(), eq(toadletContainer));
	}

	@Test
	public void sendingPostRequestWithInvalidContentLengthHeaderClosesConnection() throws IOException {
		setupInputStream("POST /invalid-content-length HTTP/1.1\r\nConnection: Keep-Alive\r\nContent-Length: invalid\r\n\r\n");
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		verify(socket).close();
	}

	private void setupInputStream(String request) throws IOException {
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(request.getBytes(UTF_8)));
	}

	private final Socket socket = mock(Socket.class, RETURNS_DEEP_STUBS);
	private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	private final ToadletContainer toadletContainer = mock(ToadletContainer.class, RETURNS_DEEP_STUBS);
	private final PageMaker pageMaker = mock(PageMaker.class, RETURNS_DEEP_STUBS);

	private final Toadlet homepageToadlet = mock(Toadlet.class, RETURNS_DEEP_STUBS);
	private final Toadlet noOutputToadlet = mock(Toadlet.class, RETURNS_DEEP_STUBS);

	{
		try {
			when(socket.getOutputStream()).thenReturn(outputStream);
			when(toadletContainer.getBucketFactory()).thenReturn(new ArrayBucketFactory());

			doAnswer(invocation -> {
				ToadletContext toadletContext = (ToadletContext) invocation.getArguments()[2];
				toadletContext.sendReplyHeaders(200, "OK", null, "text/plain", 3);
				toadletContext.writeData(new byte[] { 'O', 'K', '\n' });
				return null;
			}).when(homepageToadlet).handleMethodGET(any(), any(), any());
			when(homepageToadlet.findSupportedMethods()).thenReturn("GET");
			when(noOutputToadlet.findSupportedMethods()).thenReturn("GET");
		} catch (IOException | ToadletContextClosedException | RedirectException e) {
			throw new RuntimeException(e);
		}
	}

}
