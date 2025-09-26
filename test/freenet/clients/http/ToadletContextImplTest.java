package freenet.clients.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import org.junit.Test;

import static freenet.test.HttpResponse.parse;
import static freenet.test.HttpResponseMatchers.hasBody;
import static freenet.test.HttpResponseMatchers.hasStatus;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ToadletContextImplTest {

	@Test
	public void requestingNoOutputPageWillReturnStatus204() throws Exception {
		setupInputStream("GET / HTTP/1.0\r\n\r\n");
		when(toadletContainer.findToadlet(any())).thenReturn(noOutputToadlet);
		ToadletContextImpl.handle(socket, toadletContainer, null, null, null);
		assertThat(parse(outputStream.toByteArray()), contains(hasStatus(equalTo(204), equalTo("No Content"))));
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

	private void setupInputStream(String request) throws IOException {
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(request.getBytes(UTF_8)));
	}

	private final Socket socket = mock(Socket.class, RETURNS_DEEP_STUBS);
	private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	private final ToadletContainer toadletContainer = mock(ToadletContainer.class, RETURNS_DEEP_STUBS);

	private final Toadlet homepageToadlet = mock(Toadlet.class, RETURNS_DEEP_STUBS);
	private final Toadlet noOutputToadlet = mock(Toadlet.class, RETURNS_DEEP_STUBS);

	{
		try {
			when(socket.getOutputStream()).thenReturn(outputStream);

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
