package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.FileUtil;
import freenet.test.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.junit.Test;

import static freenet.test.HttpResponse.parse;
import static freenet.test.HttpResponseMatchers.hasBody;
import static freenet.test.HttpResponseMatchers.hasHeader;
import static freenet.test.HttpResponseMatchers.hasStatus;
import static freenet.test.HttpResponseMatchers.hasStringBody;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ToadletContextImplTest {

	@Test
	public void emptyRequestWillNotCauseAResponseToBeSent() throws Exception {
		sendRequest("", httpResponses -> {
			assertThat(httpResponses, empty());
		});
	}

	@Test
	public void requestingHomepageWillReturnHomepage() throws Exception {
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		sendRequest("GET / HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(allOf(
					hasStatus(200), hasBody(equalTo("GET OK\n".getBytes(UTF_8)))
			)));

		});
	}

	@Test
	public void requestingNoOutputPageWillReturnStatus204() throws Exception {
		when(toadletContainer.findToadlet(any())).thenReturn(noOutputToadlet);
		sendRequest("GET / HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(204), equalTo("No Content"))));
		});
	}

	@Test
	public void redirectExceptionFromToadletResultsInRedirect() throws Exception {
		Toadlet redirectingToadlet = new RedirectToadlet("/new-location");
		when(toadletContainer.findToadlet(new URI("/redirect-toadlet"))).thenReturn(redirectingToadlet);
		when(toadletContainer.findToadlet(new URI("/new-location"))).thenReturn(homepageToadlet);
		sendRequest("GET /redirect-toadlet HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(allOf(hasStatus(200), hasBody(equalTo("GET OK\n".getBytes(UTF_8))))));
		});
	}

	@Test
	public void sendingInvalidHttpRequestLineResultsInHttpStatus400() throws Exception {
		sendRequest("GET HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	@Test
	public void sendingInvalidHttpVersionResultsInHttpStatus400() throws Exception {
		sendRequest("GET / HTTP/123.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	@Test
	public void sendingInvalidUrlResultsInHttpStatus400() throws Exception {
		sendRequest("GET :invalid HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	@Test
	public void getRequestWithExtremelyLongUrlResultsInHttpStatus400() throws Exception {
		sendRequest("GET /extremely-long-url-" + generateLongString() + " HTTP/1.0\r\nContent-Length: 0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	private String generateLongString() {
		char[] longString = new char[33000];
		Arrays.fill(longString, 'a');
		return new String(longString);
	}

	@Test
	public void sendingInvalidHeaderResultsInHttpStatus400() throws Exception {
		sendRequest("GET /invalid-header HTTP/1.0\r\nInvalid-Header Yes\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	@Test
	public void endingTheRequestDuringTheHeadersWillCloseTheSocket() throws Exception {
		sendRequest("GET / HTTP/1.0\r\nHeader: Yes", httpResponses -> {
			assertThat(httpResponses, empty());
		});
	}

	@Test
	public void emptyLinesBeforeTheFirstLineAreIgnored() throws Exception {
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		sendRequest("\r\n\r\n\r\nGET / HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(allOf(
					hasStatus(equalTo(200), equalTo("OK")),
					hasBody(equalTo("GET OK\n".getBytes(UTF_8)))
			)));
		});
	}

	@Test
	public void sendingGetRequestWithContentLengthHeaderResultsInHttpStatus400() throws Exception {
		sendRequest("GET /too-much-content-length HTTP/1.0\r\nContent-Length: 1\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	@Test
	public void getRequestWithoutToadletResultsInHttpStatus404() throws Exception {
		when(toadletContainer.findToadlet(any())).thenReturn(null);
		sendRequest("GET /no-toadlet HTTP/1.1\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(404), equalTo("Not Found"))));
		});
	}

	@Test
	public void permanentRedirectExceptionWhenLocatingToadletWillResultInRedirect() throws Exception {
		when(toadletContainer.findToadlet(any())).thenThrow(new PermanentRedirectException(new URI("/new-location")));
		sendRequest("GET /redirect-from-container HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(allOf(
					hasStatus(equalTo(301), equalTo("Moved Permanently")),
					hasHeader("Location", contains("/new-location"))
			)));
		});
	}

	@Test
	public void requestWithFullAccessForwardsRequestToPageMakerForParsingAdvancedModeSwitches() throws Exception {
		when(toadletContainer.isAllowedFullAccess(any())).thenReturn(true);
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		sendRequest("GET / HTTP/1.0\r\n\r\n", httpResponses -> {
			verify(pageMaker).parseMode(any(), eq(toadletContainer));
		});
	}

	@Test
	public void requestWithoutFullAccessDoesNotForwardRequestToPageMakerForParsingAdvancedModeSwitches() throws Exception {
		when(toadletContainer.isAllowedFullAccess(any())).thenReturn(false);
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		sendRequest("GET / HTTP/1.0\r\n\r\n", httpResponses -> {
			verify(pageMaker, never()).parseMode(any(), eq(toadletContainer));
		});
	}

	@Test
	public void sendingPostRequestWithoutContentLengthHeaderResultsInHttpStatus400() throws Exception {
		sendRequest("POST /missing-content-length HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	@Test
	public void sendingPostRequestWithInvalidContentLengthHeaderResultsInHttpStatus400() throws Exception {
		sendRequest("POST /invalid-content-length HTTP/1.1\r\nContent-Length: invalid\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	@Test
	public void sendingRequestAfterPostRequestWithInvalidContentLengthHeaderResultsInASingleResponseBeingSent() throws Exception {
		sendRequest("POST /invalid-content-length HTTP/1.1\r\nContent-Length: invalid\r\n\r\nGET / HTTP/1.0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(400), equalTo("Bad Request"))));
		});
	}

	@Test
	public void postRequestWhenPostRequestsAreNotAllowedResultsInHttpStatus405() throws Exception {
		sendRequest("POST /post-not-allowed HTTP/1.0\r\nContent-Length: 0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(405), equalTo("Method Not Allowed"))));
		});
	}

	@Test
	public void postRequestWithoutFormPasswordResultsInRedirectToSameToadlet() throws Exception {
		when(toadletContainer.allowPosts()).thenReturn(true);
		when(toadletContainer.findToadlet(any())).thenReturn(postToadlet);
		when(toadletContainer.getFormPassword()).thenReturn("form-password");
		sendRequest("POST /request-without-form-password HTTP/1.0\r\nContent-Length: 0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(allOf(
					hasStatus(equalTo(302), equalTo("Found")),
					hasHeader("Location", contains("/post-toadlet"))
			)));
		});
	}

	@Test
	public void postRequestWithFormPasswordResultInToadletBeingCalled() throws Exception {
		when(toadletContainer.allowPosts()).thenReturn(true);
		when(toadletContainer.getFormPassword()).thenReturn("form-password");
		when(toadletContainer.findToadlet(any())).thenReturn(postToadlet);
		sendRequest("POST /request-with-form-password HTTP/1.0\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 26\r\n\r\nformPassword=form-password\r\n", httpResponses -> {
			assertThat(httpResponses, contains(allOf(
					hasStatus(equalTo(200), equalTo("Works")),
					hasHeader("X-Test-Header", contains("Yes")),
					hasBody(equalTo("POST OK\n".getBytes(UTF_8)))
			)));
		});
	}

	@Test
	public void postRequestWithoutFormPasswordResultInToadletBeingCalledWhenPostWithoutPasswordIsAllowed() throws Exception {
		when(toadletContainer.allowPosts()).thenReturn(true);
		when(toadletContainer.findToadlet(any())).thenReturn(postToadlet);
		postToadlet.allowPostWithoutPassword();
		sendRequest("POST /request-without-form-password HTTP/1.0\r\nContent-Length: 0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(allOf(
					hasStatus(equalTo(200), equalTo("Works")),
					hasHeader("X-Test-Header", contains("Yes")),
					hasBody(equalTo("POST OK\n".getBytes(UTF_8)))
			)));
		});
	}

	@Test
	public void postRequestWhenContainerIsPublicGatewayResultsInHttpStatus405() throws Exception {
		when(toadletContainer.publicGatewayMode()).thenReturn(true);
		when(toadletContainer.allowPosts()).thenReturn(true);
		sendRequest("POST /post-request-public-gateway HTTP/1.0\r\nContent-Length: 0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(405), equalTo("Method Not Allowed"))));
		});
	}

	@Test
	public void postRequestSucceedsWhenContainerIsPublicGatewayButStillAllowsFullAccess() throws Exception {
		when(toadletContainer.allowPosts()).thenReturn(true);
		when(toadletContainer.publicGatewayMode()).thenReturn(true);
		when(toadletContainer.findToadlet(any())).thenReturn(postToadlet);
		when(toadletContainer.isAllowedFullAccess(any())).thenReturn(true);
		postToadlet.allowPostWithoutPassword();
		sendRequest("POST /post-request-public-gateway-full-access HTTP/1.0\r\nContent-Length: 0\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(allOf(
					hasStatus(equalTo(200), equalTo("Works")),
					hasHeader("X-Test-Header", contains("Yes")),
					hasBody(equalTo("POST OK\n".getBytes(UTF_8)))
			)));

		});
	}

	@Test
	public void putRequestWithoutExtendedMethodHandlingResultsInHttpStatus403() throws Exception {
		sendRequest("PUT /no-extended-method-handling HTTP/1.1\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(403), equalTo("Forbidden"))));
		});
	}

	@Test
	public void putRequestWithContentResultsInHttpStatus403() throws Exception {
		sendRequest("PUT /put-with-content HTTP/1.1\r\nContent-Length: 3\r\n\r\nOK\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(403), equalTo("Forbidden"))));
		});
	}

	@Test
	public void putRequestToToadletThatDoesNotSupportPutResultsInHttpStatus405() throws Exception {
		when(toadletContainer.enableExtendedMethodHandling()).thenReturn(true);
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		sendRequest("PUT /toadlet-without-put HTTP/1.1\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(hasStatus(equalTo(405), equalTo("Method Not Allowed"))));
		});
	}

	@Test
	public void pipeliningTwoGetRequestsWithPersistentConnectionsEnabledResultsInTwoHttpStatus200() throws Exception {
		when(toadletContainer.enablePersistentConnections()).thenReturn(true);
		when(toadletContainer.findToadlet(any())).thenReturn(homepageToadlet);
		sendRequest("GET / HTTP/1.1\r\n\r\nGET / HTTP/1.1\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(
					allOf(hasStatus(200), hasBody(equalTo("GET OK\n".getBytes(UTF_8)))),
					allOf(hasStatus(200), hasBody(equalTo("GET OK\n".getBytes(UTF_8))))
			));
		});
	}

	@Test
	public void pipeliningAPutAndAGetRequestResultsInTwoSuccessfulRequests() throws Exception {
		when(toadletContainer.enablePersistentConnections()).thenReturn(true);
		when(toadletContainer.allowPosts()).thenReturn(true);
		postToadlet.allowPostWithoutPassword();
		when(toadletContainer.findToadlet(new URI("/post-request"))).thenReturn(postToadlet);
		when(toadletContainer.findToadlet(new URI("/get-request"))).thenReturn(homepageToadlet);
		sendRequest("POST /post-request HTTP/1.1\r\nContent-Length: 0\r\n\r\nGET /get-request HTTP/1.1\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(
					allOf(hasStatus(200), hasBody(equalTo("POST OK\n".getBytes(UTF_8)))),
					allOf(hasStatus(200), hasBody(equalTo("GET OK\n".getBytes(UTF_8))))
			));
		});
	}

	@Test
	public void requestingConnectionCloseWillReplyWithConnectionCloseWhenPipeliningIsNotAllowed() throws Exception {
		when(toadletContainer.findToadlet(URI.create("/requesting-connection-close"))).thenReturn(homepageToadlet);
		sendRequest("GET /requesting-connection-close HTTP/1.1\r\nConnection: close\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(
					hasHeader("Connection", contains(equalToIgnoringCase("close")))
			));
		});
	}

	@Test
	public void requestingConnectionKeepAliveWillReplyWithConnectionCloseWhenPipeliningIsNotAllowed() throws Exception {
		when(toadletContainer.findToadlet(URI.create("/requesting-connection-keep-alive"))).thenReturn(homepageToadlet);
		sendRequest("GET /requesting-connection-keep-alive HTTP/1.1\r\nConnection: keep-alive\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(
					hasHeader("Connection", contains(equalToIgnoringCase("close")))
			));
		});
	}

	@Test
	public void requestingConnectionCloseWillReplyWithConnectionCloseWhenPipeliningIsAllowed() throws Exception {
		when(toadletContainer.enablePersistentConnections()).thenReturn(true);
		when(toadletContainer.findToadlet(URI.create("/requesting-connection-close"))).thenReturn(homepageToadlet);
		sendRequest("GET /requesting-connection-close HTTP/1.1\r\nConnection: close\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(
					hasHeader("Connection", contains(equalToIgnoringCase("close")))
			));
		});
	}

	@Test
	public void requestingConnectionKeepAliveWillReplyWithConnectionKeepAliveWhenPipeliningIsAllowed() throws Exception {
		when(toadletContainer.enablePersistentConnections()).thenReturn(true);
		when(toadletContainer.findToadlet(URI.create("/requesting-connection-keep-alive"))).thenReturn(homepageToadlet);
		sendRequest("GET /requesting-connection-keep-alive HTTP/1.1\r\nConnection: keep-alive\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(
					hasHeader("Connection", contains(equalToIgnoringCase("keep-alive")))
			));
		});
	}

	@Test
	public void ioExceptionInGetMethodOfToadletWillCauseHttpStatus500() throws Exception {
		when(toadletContainer.findToadlet(new URI("/error-in-get"))).thenReturn(errorToadlet);
		sendRequest("GET /error-in-get HTTP/1.1\r\n\r\n", httpResponses -> {
			assertThat(httpResponses, contains(
					allOf(hasStatus(500), hasStringBody(containsString("java.io.IOException: Test GET")))
			));
		});
	}

	@Test
	public void ioExceptionInPostMethodOfToadletWillCauseHttpStatus500() throws Exception {
		when(toadletContainer.allowPosts()).thenReturn(true);
		when(toadletContainer.getFormPassword()).thenReturn("form-password");
		when(toadletContainer.findToadlet(new URI("/error-in-post"))).thenReturn(errorToadlet);
		sendRequest("POST /error-in-post HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 26\r\n\r\nformPassword=form-password\r\n", httpResponses -> {
			assertThat(httpResponses, contains(
					allOf(hasStatus(500), hasStringBody(containsString("java.io.IOException: Test POST")))
			));
		});
	}

	private void sendRequest(String request, Consumer<List<HttpResponse>> test) throws IOException, InterruptedException {
		CountDownLatch readFinished = new CountDownLatch(1);
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			new Thread(() -> {
				try (Socket socket = serverSocket.accept()) {
					ToadletContextImpl.handle(socket, toadletContainer, pageMaker, null, null);
					if (!socket.isClosed()) {
						socket.shutdownOutput();
					}
					readFinished.await();
				} catch (IOException | InterruptedException e) {
					throw new RuntimeException(e);
				}
			}).start();
			try (Socket clientSocket = new Socket("localhost", serverSocket.getLocalPort());
			     InputStream clientInputStream = clientSocket.getInputStream();
			     OutputStream clientOutputStream = clientSocket.getOutputStream()) {
				clientOutputStream.write(request.getBytes(UTF_8));
				clientOutputStream.flush();
				clientSocket.shutdownOutput();
				try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
					FileUtil.copy(clientInputStream, outputStream, -1);
					readFinished.countDown();
					List<HttpResponse> httpResponses = parse(outputStream.toByteArray());
					test.accept(httpResponses);
				}
			}
		}
	}

	private final ToadletContainer toadletContainer = mock(ToadletContainer.class, RETURNS_DEEP_STUBS);
	private final PageMaker pageMaker = mock(PageMaker.class, RETURNS_DEEP_STUBS);

	private final Toadlet homepageToadlet = new HomepageToadlet();
	private final Toadlet noOutputToadlet = new NoOutputToadlet();
	private final PostToadlet errorToadlet = new ErrorToadlet();
	private final PostToadlet postToadlet = new PostToadlet();

	{
		when(toadletContainer.getBucketFactory()).thenReturn(new ArrayBucketFactory());
	}

	private static class RedirectToadlet extends Toadlet {

		private final String redirectTarget;

		@Override
		public void handleMethodGET(URI uri, HTTPRequest httpRequest, ToadletContext toadletContext) throws RedirectException {
			throw new RedirectException(URI.create(redirectTarget));
		}

		@Override
		public String path() {
			return "/redirect-toadlet";
		}

		RedirectToadlet(String redirectTarget) {
			super(mock(HighLevelSimpleClient.class));
			this.redirectTarget = redirectTarget;
		}

	}

	private static class HomepageToadlet extends Toadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest httpRequest, ToadletContext toadletContext) throws ToadletContextClosedException, IOException {
			toadletContext.sendReplyHeaders(200, "OK", null, "text/plain", 7);
			toadletContext.writeData(new byte[]{'G', 'E', 'T', ' ', 'O', 'K', '\n'});
		}

		@Override
		public String path() {
			return "/homepage-toadlet";
		}

		HomepageToadlet() {
			super(mock(HighLevelSimpleClient.class));
		}

	}

	private static class NoOutputToadlet extends Toadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest httpRequest, ToadletContext toadletContext) {
			// no output
		}

		@Override
		public String path() {
			return "/no-output";
		}

		NoOutputToadlet() {
			super(mock(HighLevelSimpleClient.class));
		}

	}

	private static class PostToadlet extends Toadlet {

		@SuppressWarnings("unused")
		public void handleMethodPOST(URI uri, HTTPRequest httpRequest, ToadletContext toadletContext) throws ToadletContextClosedException, IOException {
			toadletContext.sendReplyHeaders(200, "Works", MultiValueTable.from("X-Test-Header", "Yes"), "text/plain", 8);
			toadletContext.writeData("POST OK\n".getBytes(UTF_8));
		}

		@Override
		public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws IOException {
		}

		@Override
		public String path() {
			return "/post-toadlet";
		}

		@Override
		public boolean allowPOSTWithoutPassword() {
			return allowPostWithoutPassword;
		}

		private void allowPostWithoutPassword() {
			allowPostWithoutPassword = true;
		}

		protected PostToadlet() {
			super(mock(HighLevelSimpleClient.class));
		}

		private boolean allowPostWithoutPassword = false;

	}

	private static class ErrorToadlet extends PostToadlet {

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest httpRequest, ToadletContext toadletContext) throws IOException {
			throw new IOException("Test POST");
		}

		@Override
		public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws IOException {
			throw new IOException("Test GET");
		}

	}

}
