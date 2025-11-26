package freenet.clients.http;

import freenet.clients.http.bookmark.BookmarkManager;
import freenet.node.Node;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * A test implementation of {@link ToadletContext}. Can and should only be
 * used for testing {@link Toadlet} implementations.
 *
 * <h2>Usage</h2>
 *
 * <p>
 * The {@link TestToadletContext} takes care of creating all necessary
 * objects for a call of a {@code handleMethod*} method and even runs the
 * method in question itself. The HTTP status code, response headers, and
 * body data can afterward be retrieved from the {@link TestToadletContext}
 * instance, like this:
 * </p>
 * <pre>
 *     TestToadletContext toadletContext = TestToadletContext.build()
 *     	.forToadlet(toadlet)
 *     	.build();
 *     toadletContext.handleRequest();
 *     assertThat(toadletContext.getStatusCode(), equalTo(404));
 * </pre>
 * <p>
 * Implementation note: This class <em>should</em> live in
 * {@link freenet.test}, but because
 * {@link PageMaker#PageMaker(PageMaker.THEME, Node) this constructor}
 * is {@code protected}, it has to live in <em>this</em> package.
 * </p>
 * <p>
 * This implementation is quite incomplete! Many of the methods
 * are empty and/or return {@code null}! If you find that a specific
 * method is causing problems in your test, I guess you will have to
 * continue this implementation. 😄(I have put comments into methods
 * that are currently unimplemented; if you implement them, don’t
 * forget to remove the comment!)
 * </p>
 */
public class TestToadletContext implements ToadletContext {

	/**
	 * Returns a new {@link TestToadletContextBuilder}.
	 *
	 * @return A new builder
	 */
	public static TestToadletContextBuilder builder() {
		return new TestToadletContextBuilder();
	}

	/**
	 * Builder for {@link TestToadletContext} instances.
	 * <p>
	 * Instances of this class can theoretically be reused; created instances
	 * will be independent of one another. However, the syntax has been
	 * geared towards a fluid builder interface.
	 * </p>
	 * <h2>Usage</h2>
	 * <pre>
	 * TestToadletContext.build()
	 * 	.forToadlet(toadletUnderTest)
	 * 	.requesting("/test-uri")
	 * 	.withHeader("Some-Header", "Some Value")
	 * 	.build();
	 * </pre>
	 * <p>
	 * The {@link #withNode(Node)} and
	 * {@link #withContainer(ToadletContainer)} methods can be used to
	 * set these objects to specific values, e.g. when given to the toadlet
	 * by different means; if unused, default mock instances will be used.
	 * </p>
	 * <p>
	 * In addition to things you would expect, this builder also does a
	 * number of things that one might not expect, such as setting the
	 * {@link Toadlet#container} field of the
	 * {@link #forToadlet(Toadlet) configured Toadlet} to the given
	 * {@link #withContainer(ToadletContainer) ToadletContainer}.
	 * </p>
	 * <p>
	 * Instances of this class should be retrieved from
	 * {@link TestToadletContext#builder() TestToadletContext.builder()}.
	 * </p>
	 */
	public static class TestToadletContextBuilder {

		public TestToadletContextBuilder forToadlet(Toadlet toadlet) {
			this.activeToadlet = toadlet;
			return this;
		}

		public TestToadletContextBuilder requesting(String uri) {
			this.uri = URI.create(uri);
			return this;
		}

		public TestToadletContextBuilder withHeader(String name, String value) {
			requestHeaders.computeIfAbsent(name.toLowerCase(), _ignored -> new ArrayList<>()).add(value);
			return this;
		}

		public TestToadletContextBuilder withNode(Node node) {
			this.node = node;
			return this;
		}

		public TestToadletContextBuilder withContainer(ToadletContainer toadletContainer) {
			this.toadletContainer = toadletContainer;
			return this;
		}

		public TestToadletContext build() {
			activeToadlet.container = toadletContainer;
			return new TestToadletContext(node, toadletContainer, activeToadlet, uri, method, fromMap(requestHeaders));
		}

		private static MultiValueTable<String, String> fromMap(Map<String, List<String>> headers) {
			MultiValueTable<String, String> multiValueTable = new MultiValueTable<>();
			headers.forEach(multiValueTable::putAll);
			return multiValueTable;
		}

		private URI uri;
		private String method = "GET";
		private Toadlet activeToadlet = mock(Toadlet.class, RETURNS_DEEP_STUBS);
		private final Map<String, List<String>> requestHeaders = new HashMap<>();
		private Node node = mock(Node.class, RETURNS_DEEP_STUBS);
		private ToadletContainer toadletContainer = mock(ToadletContainer.class, RETURNS_DEEP_STUBS);

	}

	/**
	 * Returns the HTTP status code of the response.
	 *
	 * @return The HTTP status code
	 */
	public int getStatusCode() {
		return statusCode;
	}

	/**
	 * Returns the reason phrase from the HTTP status line.
	 *
	 * @return The reason phrase
	 */
	public String getReasonPhrase() {
		return reasonPhrase;
	}

	/**
	 * Returns the headers of the toadlet’s response. All keys are converted
	 * to lower-case!
	 *
	 * @return The response headers
	 */
	public Map<String, List<String>> getResponseHeaders() {
		return responseHeaders;
	}

	/**
	 * Returns the body set by the {@link Toadlet}.
	 *
	 * @return The body, or {@code null} if no body is present
	 */
	public byte[] getBody() {
		return body;
	}

	/**
	 * Returns the content of the body as a {@link String}, assuming an
	 * {@link StandardCharsets#UTF_8 UTF-8} encoding.
	 *
	 * @return The body as {@link String}, or {@code null} if no body is
	 * 		present
	 */
	public String getBodyText() {
		return (body != null) ? new String(body, UTF_8) : null;
	}

	public void handleRequest() throws ToadletContextClosedException, IOException, RedirectException {
		HTTPRequest httpRequest = new HTTPRequestImpl(uri, method);
		if (method.equals("GET")) {
			activeToadlet.handleMethodGET(getUri(), httpRequest, this);
		}
	}

	@Override
	public void sendReplyHeaders(int code, String desc, MultiValueTable<String, String> mvt, String mimeType, long length) throws ToadletContextClosedException, IOException {
		sendReplyHeaders(code, desc, mvt, mimeType, length, false);
	}

	@Override
	public void sendReplyHeaders(int code, String desc, MultiValueTable<String, String> mvt, String mimeType, long length, boolean forceDisableJavascript) throws ToadletContextClosedException, IOException {
		sendReplyHeaders(code, desc, mvt, mimeType, length, new Date());
	}

	@Override
	public void sendReplyHeaders(int code, String desc, MultiValueTable<String, String> mvt, String mimeType, long length, Date mTime) throws ToadletContextClosedException, IOException {
		sendReplyHeadersStatic(code, desc, mvt, mimeType, length, mTime);
	}

	@Override
	public void sendReplyHeadersStatic(int code, String desc, MultiValueTable<String, String> mvt, String mimeType, long length, Date mTime) throws ToadletContextClosedException, IOException {
		statusCode = code;
		reasonPhrase = desc;
		if (mvt != null) {
			mvt.entrySet().forEach(header -> header.getValue().forEach(value -> {
				responseHeaders.computeIfAbsent(header.getKey().toLowerCase(), _ignored -> new ArrayList<>()).add(value);
			}));
		}
		if (mimeType != null) {
			responseHeaders.computeIfAbsent("content-type", _ignored -> new ArrayList<>()).add(mimeType);
		}
		if (length >= 0) {
			responseHeaders.computeIfAbsent("content-length", _ignored -> new ArrayList<>()).add(Long.toString(length));
		}
	}

	@Override
	public void sendReplyHeadersFProxy(int code, String desc, MultiValueTable<String, String> mvt, String mimeType, long length) throws ToadletContextClosedException, IOException {
		sendReplyHeadersStatic(code, desc, mvt, mimeType, length, new Date());
	}

	@Override
	public void writeData(byte[] data, int offset, int length) throws ToadletContextClosedException, IOException {
		body = Arrays.copyOfRange(data, offset, length);
	}

	@Override
	public void forceDisconnect() {
		// Not yet implemented
	}

	@Override
	public void writeData(byte[] data) throws ToadletContextClosedException, IOException {
		writeData(data, 0, data.length);
	}

	@Override
	public void writeData(Bucket data) throws ToadletContextClosedException, IOException {
		try (ByteArrayOutputStream byteOutput = new ByteArrayOutputStream()) {
			try (InputStream inputStream = data.getInputStream()) {
				FileUtil.copy(inputStream, byteOutput, data.size());
			}
			writeData(byteOutput.toByteArray());
		}
	}

	@Override
	public PageMaker getPageMaker() {
		return new PageMaker(PageMaker.THEME.getDefault(), node);
	}

	@Override
	public String getFormPassword() {
		// Not yet implemented
		return "";
	}

	@Override
	public boolean checkFormPassword(HTTPRequest request, String redirectTo) throws ToadletContextClosedException, IOException {
		// Not yet implemented
		return false;
	}

	@Override
	public boolean checkFormPassword(HTTPRequest request) throws ToadletContextClosedException, IOException {
		// Not yet implemented
		return false;
	}

	@Override
	public boolean hasFormPassword(HTTPRequest request) throws IOException {
		// Not yet implemented
		return false;
	}

	@Override
	public boolean checkFullAccess(Toadlet toadlet) throws ToadletContextClosedException, IOException {
		// Not yet implemented
		return false;
	}

	@Override
	public UserAlertManager getAlertManager() {
		return userAlertManager;
	}

	@Override
	public BookmarkManager getBookmarkManager() {
		// Not yet implemented
		return null;
	}

	@Override
	public BucketFactory getBucketFactory() {
		// Not yet implemented
		return null;
	}

	@Override
	public MultiValueTable<String, String> getHeaders() {
		return requestHeaders;
	}

	@Override
	public ReceivedCookie getCookie(URI domain, URI path, String name) throws ParseException {
		// Not yet implemented
		return null;
	}

	@Override
	public void setCookie(Cookie newCookie) {
		// Not yet implemented
	}

	@Override
	public HTMLNode addFormChild(HTMLNode parentNode, String target, String id) {
		HTMLNode formNode = parentNode.addChild("form");
		formNode.addAttribute("target", target);
		formNode.addAttribute("id", id);
		return formNode;
	}

	@Override
	public boolean isAllowedFullAccess() {
		// Not yet implemented
		return false;
	}

	@Override
	public boolean isAdvancedModeEnabled() {
		// Not yet implemented
		return false;
	}

	@Override
	public boolean doRobots() {
		// Not yet implemented
		return false;
	}

	@Override
	public ToadletContainer getContainer() {
		return toadletContainer;
	}

	@Override
	public boolean disableProgressPage() {
		// Not yet implemented
		return false;
	}

	@Override
	public Toadlet activeToadlet() {
		return activeToadlet;
	}

	@Override
	public String getUniqueId() {
		// Not yet implemented
		return "";
	}

	@Override
	public URI getUri() {
		return uri;
	}

	@Override
	public FProxyFetchInProgress.REFILTER_POLICY getReFilterPolicy() {
		return null;
	}

	private TestToadletContext(Node node, ToadletContainer toadletContainer, Toadlet activeToadlet, URI uri, String method, MultiValueTable<String, String> requestHeaders) {
		this.node = node;
		this.toadletContainer = toadletContainer;
		this.userAlertManager = node.getClientCore().getAlerts();
		this.activeToadlet = activeToadlet;
		this.uri = uri;
		this.method = method;
		this.requestHeaders = requestHeaders;
	}

	private final Node node;
	private final ToadletContainer toadletContainer;
	private final UserAlertManager userAlertManager;
	private final Toadlet activeToadlet;
	private final URI uri;
	private final String method;
	private final MultiValueTable<String, String> requestHeaders;

	private final Map<String, List<String>> responseHeaders = new HashMap<>();
	private int statusCode = -1;
	private String reasonPhrase;
	private byte[] body = null;

}
