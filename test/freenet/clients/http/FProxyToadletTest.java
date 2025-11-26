package freenet.clients.http;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.KnownUnsafeContentTypeException;
import freenet.clients.http.TestToadletContext.TestToadletContextBuilder;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10nTest;
import freenet.node.NodeClientCore;
import freenet.node.RequestClientBuilder;
import freenet.support.io.ArrayBucket;
import java.io.File;
import java.net.URI;
import java.util.function.Consumer;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.FieldSetter;

import static freenet.test.HtmlMatchers.hasAttribute;
import static freenet.test.HtmlMatchers.hasElement;
import static freenet.test.HtmlMatchers.hasTitle;
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
import static freenet.test.ToadletContextMatchers.isHtml;
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
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting("/KSK@test"), allOf(
				hasStatus(equalTo(500)),
				isHtml(
						hasElement("li a:contains(open-as-text)",
								hasAttribute("href",
										isURI(hasQuery(isKeyValuePairs(hasEntry(equalTo("type"), contains(isMimeType(hasParameter("charset", equalToIgnoringCase("utf-8")))))))))))
		));
	}

	@Test
	public void downloadedFileIsDeliveredImmediately() throws Exception {
		when(fetchTracker.makeFetcher(any(), anyLong(), any(), any())).then(invocation -> {
			FProxyFetchInProgress fetchInProgress = new FProxyFetchInProgress(fetchTracker, invocation.getArgument(0, FreenetURI.class), invocation.getArgument(1, Long.class), 0, null, invocation.getArgument(2, FetchContext.class), new RequestClientBuilder().build(), invocation.getArgument(3, FProxyFetchInProgress.REFILTER_POLICY.class));
			fetchInProgress.onSuccess(new FetchResult(new ClientMetadata("text/plain"), new ArrayBucket("test".getBytes(UTF_8))), null);
			return new FProxyFetchWaiter(fetchInProgress);
		});
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting("/KSK@test"), allOf(
				hasStatus(equalTo(200)),
				hasBodyText(equalTo("test")),
				hasHeader("Content-Type", contains(isMimeType(hasBaseType("text/plain"))))
		));
	}

	@Test
	public void requestingInvalidKeyViaParameterResultsInErrorPage() throws Exception {
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting("/?key=INV@lid"), hasStatus(equalTo(404)));
	}

	@Test
	public void requestingAValidKeyViaParameterResultsInRedirect() throws Exception {
		verifyTemporaryRedirect("/?key=KSK@test", "/freenet:KSK@test");
	}

	@Test
	public void requestingTheRootUrlRedirectsToTheWelcomeToadlet() {
		verifyInternalRedirect("/", "/welcome/");
	}

	@Test
	public void requestingFaviconIcoRedirectsToStaticToadlet() {
		verifyInternalRedirect("/favicon.ico", "/static/favicon.ico");
	}

	@Test
	public void requestingFeedSendsTheAtomFeed() throws Exception {
		when(nodeClientCore.getAlerts().getAtom(any())).thenReturn("atom-feed");
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting("/feed"), allOf(
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
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting("/robots.txt").doRobots(), allOf(
				hasStatus(equalTo(200)),
				hasContentType(hasBaseType("text/plain")),
				hasBodyText(equalToIgnoringCase("User-Agent: *\nDisallow: /"))
		));
	}

	@Test
	public void requestingRobotsFileWithRobotsFileDisabledReturnsClientError() throws Exception {
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting("/robots.txt"), hasStatus(allOf(greaterThanOrEqualTo(400), lessThan(500))));
	}

	@Test
	public void requestDarknetPageWithoutSlashRedirectsToFriendsPage() throws Exception {
		verifyPermanentRedirect("/darknet", "/friends/");
	}

	@Test
	public void requestDarknetPageWithSlashRedirectsToFriendsPage() throws Exception {
		verifyPermanentRedirect("/darknet/", "/friends/");
	}

	@Test
	public void requestOpennetPageWithoutSlashRedirectsToStrangersPage() throws Exception {
		verifyPermanentRedirect("/opennet", "/strangers/");
	}

	@Test
	public void requestOpennetPageWithSlashRedirectsToStrangersPage() throws Exception {
		verifyPermanentRedirect("/opennet/", "/strangers/");
	}

	@Test
	public void requestQueuePageRedirectsToDownloadsPage() throws Exception {
		verifyPermanentRedirect("/queue/", "/downloads/");
	}

	@Test
	public void requestConfigPageRedirectsToConfigNodePage() throws Exception {
		verifyPermanentRedirect("/config/", "/config/node");
	}

	@Test
	public void requestingAFailedDownloadReturnsErrorPage() throws Exception {
		BaseL10nTest.useTestTranslation();
		when(fetchTracker.makeFetcher(any(), anyLong(), any(), any())).then(invocation -> {
			FProxyFetchInProgress fetchInProgress = new FProxyFetchInProgress(fetchTracker, invocation.getArgument(0, FreenetURI.class), invocation.getArgument(1, Long.class), 0, null, invocation.getArgument(2, FetchContext.class), new RequestClientBuilder().build(), invocation.getArgument(3, FProxyFetchInProgress.REFILTER_POLICY.class));
			fetchInProgress.onFailure(new FetchException(FetchException.FetchExceptionMode.ALL_DATA_NOT_FOUND), null);
			return new FProxyFetchWaiter(fetchInProgress);
		});
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting("/KSK@failed"), allOf(
				hasStatus(equalTo(500)),
				isHtml(allOf(
						hasTitle(equalTo("FetchException.shortError.28 - Freenet")),
						hasElement("p:contains(FProxyToadlet.unableToRetrieve)")
				))
		));
	}

	@Test
	public void requestingAnInProgressKeyReturnsProgressPage() throws Exception {
		BaseL10nTest.useTestTranslation();
		when(fetchTracker.makeFetcher(any(), anyLong(), any(), any())).then(invocation -> {
			FProxyFetchInProgress fetchInProgress = new FProxyFetchInProgress(fetchTracker, invocation.getArgument(0, FreenetURI.class), invocation.getArgument(1, Long.class), 0, null, invocation.getArgument(2, FetchContext.class), new RequestClientBuilder().build(), invocation.getArgument(3, FProxyFetchInProgress.REFILTER_POLICY.class));
			fetchInProgress.setHasWaited();
			return new FProxyFetchWaiter(fetchInProgress);
		});
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting("/KSK@test").withHeader("User-Agent", "Mozilla/Test"), allOf(
				isHtml(
						hasTitle(equalTo("FProxyToadlet.fetchingPageTitle - Freenet"))
				)
		));
	}

	private void verifyTemporaryRedirect(String fromUri, String toUri) throws Exception {
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting(fromUri), allOf(
				hasStatus(equalTo(302)),
				hasHeader("Location", contains(equalTo(toUri)))
		));
	}

	private void verifyPermanentRedirect(String fromUri, String toUri) throws Exception {
		createToadletContextExecuteRequestAndVerifyContext(builder -> builder.requesting(fromUri), allOf(
				hasStatus(equalTo(301)),
				hasHeader("Location", contains(equalTo(toUri)))
		));
	}

	private void verifyInternalRedirect(String fromUri, String toUri) {
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting(fromUri)
				.withNode(nodeClientCore.getNode()).build();
		RedirectException redirectException = assertThrows(RedirectException.class, toadletContext::handleRequest);
		assertThat(redirectException.getTarget(), equalTo(URI.create(toUri)));
	}

	private void createToadletContextExecuteRequestAndVerifyContext(Consumer<TestToadletContextBuilder> builderCustomizer, Matcher<TestToadletContext> toadletContextMatcher) throws Exception {
		TestToadletContextBuilder builder = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.withNode(nodeClientCore.getNode());
		builderCustomizer.accept(builder);
		TestToadletContext toadletContext = builder.build();
		toadletContext.handleRequest();
		assertThat(toadletContext, toadletContextMatcher);
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
