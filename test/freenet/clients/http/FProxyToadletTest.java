package freenet.clients.http;

import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClient;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.KnownUnsafeContentTypeException;
import freenet.l10n.BaseL10nTest;
import freenet.node.NodeClientCore;
import freenet.support.api.HTTPRequest;
import java.io.File;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;
import org.mockito.internal.util.reflection.FieldSetter;

import static freenet.test.LinkMatchers.hasParameter;
import static freenet.test.LinkMatchers.hasQuery;
import static freenet.test.LinkMatchers.isKeyValuePairs;
import static freenet.test.LinkMatchers.isMimeType;
import static freenet.test.LinkMatchers.isURI;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.hasEntry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FProxyToadletTest {

	@Test
	public void whenOfferingFilterOptionsPlainTextMediaTypeHasCharsetUtf8() throws Exception {
		BaseL10nTest.useTestTranslation("freenet/clients/http/FProxyToadletTest.properties");
		HighLevelSimpleClient highLevelSimpleClient = mock(HighLevelSimpleClient.class, RETURNS_DEEP_STUBS);
		NodeClientCore nodeClientCore = mock(NodeClientCore.class, RETURNS_DEEP_STUBS);
		when(nodeClientCore.getNode().getConfig().get("fproxy").getOption("port").getValueString()).thenReturn("8888");
		when(nodeClientCore.getNode().getConfig().get("fproxy").getOption("bindTo").getValueString()).thenReturn("127.0.0.1,0:0:0:0:0:0:0:1");
		when(nodeClientCore.getAllowedDownloadDirs()).thenReturn(new File[] { new File("/test") });
		FProxyFetchTracker fetchTracker = mock(FProxyFetchTracker.class, RETURNS_DEEP_STUBS);
		FetchException fetchException = (FetchException) new FetchException(FetchException.FetchExceptionMode.UNKNOWN_METADATA, 4, true, "application/pdf")
				.initCause(new KnownUnsafeContentTypeException(ContentFilter.getMIMEType("application/pdf")));
		when(fetchTracker.makeFetcher(any(), anyLong(), any(), any())).thenThrow(fetchException);
		FProxyToadlet fProxyToadlet = new FProxyToadlet(highLevelSimpleClient, nodeClientCore, fetchTracker);
		FieldSetter.setField(fProxyToadlet, FProxyToadlet.class.getDeclaredField("random"), new byte[0]);
		HTTPRequest httpRequest = mock(HTTPRequest.class, RETURNS_DEEP_STUBS);
		TestToadletContext toadletContext = TestToadletContext.builder()
				.forToadlet(fProxyToadlet)
				.requesting("/KSK@test")
				.withNode(nodeClientCore.getNode())
				.build();
		fProxyToadlet.handleMethodGET(toadletContext.getUri(), httpRequest, toadletContext);
		Document document = Jsoup.parse(toadletContext.getBodyText());
		String link = document.selectFirst("li a:contains(open-as-text)").attr("href");
		assertThat(link, isURI(hasQuery(isKeyValuePairs(hasEntry(equalTo("type"), contains(isMimeType(hasParameter("charset", equalToIgnoringCase("utf-8")))))))));
	}

}
