package freenet.clients.http;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.KnownUnsafeContentTypeException;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10nTest;
import freenet.node.NodeClientCore;
import freenet.node.RequestClientBuilder;
import freenet.support.io.ArrayBucket;
import java.io.File;
import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.FieldSetter;

import static freenet.test.LinkMatchers.hasBaseType;
import static freenet.test.LinkMatchers.hasParameter;
import static freenet.test.LinkMatchers.hasQuery;
import static freenet.test.LinkMatchers.isKeyValuePairs;
import static freenet.test.LinkMatchers.isMimeType;
import static freenet.test.LinkMatchers.isURI;
import static freenet.test.ToadletContextMatchers.hasBodyText;
import static freenet.test.ToadletContextMatchers.hasContentType;
import static freenet.test.ToadletContextMatchers.hasHeader;
import static freenet.test.ToadletContextMatchers.hasStatus;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FProxyToadletTest {

	@Test
	public void whenOfferingFilterOptionsPlainTextMediaTypeHasCharsetUtf8() throws Exception {
		BaseL10nTest.useTestTranslation("freenet/clients/http/FProxyToadletTest.properties");
		FetchException fetchException = (FetchException) new FetchException(FetchException.FetchExceptionMode.UNKNOWN_METADATA, 4, true, "application/pdf")
				.initCause(new KnownUnsafeContentTypeException(ContentFilter.getMIMEType("application/pdf")));
		when(fetchTracker.makeFetcher(any(), anyLong(), any(), any())).thenThrow(fetchException);
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/KSK@test")
				.withNode(nodeClientCore.getNode())
				.build();
		toadletContext.handleRequest();
		assertThat(toadletContext, allOf(
				hasStatus(equalTo(500)),
				hasHeader("Content-Type", contains(isMimeType(hasBaseType("text/html"))))
		));
		Document document = Jsoup.parse(toadletContext.getBodyText());
		String link = document.selectFirst("li a:contains(open-as-text)").attr("href");
		assertThat(link, isURI(hasQuery(isKeyValuePairs(hasEntry(equalTo("type"), contains(isMimeType(hasParameter("charset", equalToIgnoringCase("utf-8")))))))));
	}

	@Test
	public void downloadedFileIsDeliveredImmediately() throws Exception {
		when(fetchTracker.makeFetcher(any(), anyLong(), any(), any())).then(invocation -> {
			FProxyFetchInProgress fetchInProgress = new FProxyFetchInProgress(fetchTracker, invocation.getArgument(0, FreenetURI.class), invocation.getArgument(1, Long.class), 0, null, invocation.getArgument(2, FetchContext.class), new RequestClientBuilder().build(), invocation.getArgument(3, FProxyFetchInProgress.REFILTER_POLICY.class));
			fetchInProgress.onSuccess(new FetchResult(new ClientMetadata("text/plain"), new ArrayBucket("test".getBytes(UTF_8))), null);
			return new FProxyFetchWaiter(fetchInProgress);
		});
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/KSK@test")
				.withNode(nodeClientCore.getNode())
				.build();
		toadletContext.handleRequest();
		assertThat(toadletContext, allOf(
				hasStatus(equalTo(200)),
				hasBodyText(equalTo("test")),
				hasHeader("Content-Type", contains(isMimeType(hasBaseType("text/plain"))))
		));
	}

	@Test
	public void requestingInvalidKeyViaParameterResultsInErrorPage() throws Exception {
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/?key=INV@alid.key")
				.withNode(nodeClientCore.getNode())
				.build();
		toadletContext.handleRequest();
		assertThat(toadletContext, allOf(
				hasStatus(equalTo(404))
		));
	}

	@Test
	public void requestingAValidKeyViaParameterResultsInRedirect() throws Exception {
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/?key=KSK@test")
				.withNode(nodeClientCore.getNode())
				.build();
		toadletContext.handleRequest();
		assertThat(toadletContext, allOf(
				hasStatus(equalTo(302)),
				hasHeader("Location", contains(equalTo("/freenet:KSK@test")))
		));
	}

	@Test
	public void requestingTheRootUrlRedirectsToTheWelcomeToadlet() throws Exception {
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/")
				.withNode(nodeClientCore.getNode())
				.build();
		RedirectException redirectException = assertThrows(RedirectException.class, toadletContext::handleRequest);
		assertThat(redirectException.getTarget(), equalTo(URI.create("/welcome/")));
	}

	@Test
	public void requestingFaviconIcoRedirectsToStaticToadlet() throws Exception {
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/favicon.ico")
				.withNode(nodeClientCore.getNode())
				.build();
		RedirectException redirectException = assertThrows(RedirectException.class, toadletContext::handleRequest);
		assertThat(redirectException.getTarget(), equalTo(URI.create("/static/favicon.ico")));
	}

	@Test
	public void requestingFeedSendsTheAtomFeed() throws Exception {
		when(nodeClientCore.getAlerts().getAtom(any())).thenReturn("atom-feed");
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/feed")
				.withNode(nodeClientCore.getNode())
				.build();
		toadletContext.handleRequest();
		assertThat(toadletContext, allOf(
				hasStatus(equalTo(200)),
				hasContentType(hasBaseType("application/atom+xml")),
				hasBodyText(equalTo("atom-feed"))
		));
	}

	@Test
	public void requestingFeedGeneratesUrlPrefixCorrectly() throws Exception {
		when(nodeClientCore.getAlerts().getAtom(any())).thenReturn("atom-feed");
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/feed/")
				.withNode(nodeClientCore.getNode())
				.build();
		toadletContext.handleRequest();
		ArgumentCaptor<String> urlPrefixCaptor = ArgumentCaptor.forClass(String.class);
		verify(nodeClientCore.getAlerts()).getAtom(urlPrefixCaptor.capture());
		assertThat(urlPrefixCaptor.getValue(), equalTo("http://127.0.0.1:8888"));
	}

	@Test
	public void requestingRobotsFileReturnsADisallowForEverything() throws Exception {
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/robots.txt")
				.doRobots()
				.withNode(nodeClientCore.getNode())
				.build();
		toadletContext.handleRequest();
		assertThat(toadletContext, allOf(
				hasStatus(equalTo(200)),
				hasContentType(hasBaseType("text/plain")),
				hasBodyText(equalToIgnoringCase("User-Agent: *\nDisallow: /"))
		));
	}

	@Test
	public void requestingRobotsFileWithRobotsFileDisabledReturnsClientError() throws Exception {
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/robots.txt")
				.withNode(nodeClientCore.getNode())
				.build();
		toadletContext.handleRequest();
		assertThat(toadletContext, allOf(
				hasStatus(allOf(greaterThanOrEqualTo(400), lessThan(500)))
		));
	}

	private final HighLevelSimpleClient highLevelSimpleClient = mock(HighLevelSimpleClient.class, RETURNS_DEEP_STUBS);
	private final NodeClientCore nodeClientCore = mock(NodeClientCore.class, RETURNS_DEEP_STUBS);
	private final FProxyFetchTracker fetchTracker = mock(FProxyFetchTracker.class, RETURNS_DEEP_STUBS);

	{
		when(nodeClientCore.getNode().getClientCore()).thenReturn(nodeClientCore);
		when(nodeClientCore.getNode().getConfig().get("fproxy").getOption("port").getValueString()).thenReturn("8888");
		when(nodeClientCore.getNode().getConfig().get("fproxy").getOption("bindTo").getValueString()).thenReturn("127.0.0.1,0:0:0:0:0:0:0:1");
		when(nodeClientCore.getAllowedDownloadDirs()).thenReturn(new File[] { new File("/test") });
	}

	private final FProxyToadlet fProxyToadlet = new FProxyToadlet(highLevelSimpleClient, nodeClientCore, fetchTracker);

	{
		try {
			FieldSetter.setField(fProxyToadlet, FProxyToadlet.class.getDeclaredField("random"), new byte[0]);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}

}
