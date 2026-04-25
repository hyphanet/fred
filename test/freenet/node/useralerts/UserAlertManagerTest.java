package freenet.node.useralerts;

import static freenet.test.Matchers.isHtml;
import static freenet.test.Matchers.withElement;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import freenet.node.DarknetPeerNode;
import freenet.node.PeerNode;
import freenet.support.HTMLNode;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import freenet.l10n.BaseL10nTest;
import freenet.node.NodeClientCore;

public class UserAlertManagerTest {

	@Test
	public void generatedAtomContainsTitle() throws Exception {
		BaseL10nTest.useTestTranslation();
		verifyStringPropertyInGeneratedAtom("/feed/title", equalTo("UserAlertManager.feedTitle"));
	}

	@Test
	public void generatedAtomContainsLinkToItself() throws Exception {
		verifyStringPropertyInGeneratedAtom("/feed/link[@rel='self']/@href", equalTo("http://test/feed/"));
	}

	@Test
	public void generatedAtomContainsLinkToContext() throws Exception {
		verifyStringPropertyInGeneratedAtom("/feed/link[count(@rel)=0]/@href", equalTo("http://test"));
	}

	@Test
	public void generatedAtomContainsLastUpdateTime() throws Exception {
		verifyStringPropertyInGeneratedAtom("/feed/updated", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}"));
	}

	@Test
	public void generatedAtomContainsNodeId() throws Exception {
		verifyStringPropertyInGeneratedAtom("/feed/id", equalTo("urn:node:AQIDBA"));
	}

	@Test
	public void generatedAtomContainsLogoUrl() throws Exception {
		verifyStringPropertyInGeneratedAtom("/feed/logo", equalTo("/favicon.ico"));
	}

	@Test
	public void generatedAtomContainsUserAlerts() throws Exception {
		SimpleUserAlert alert = new SimpleUserAlert(true, "Alert Title", "This is an alert!", "It’s an alert!", (short) 0);
		userAlertManager.register(alert);
		List<Node> nodes = getEntryNodes(createAtomXml());
		assertThat(nodes, contains(representsUserAlert(alert)));
	}

	@Test
	public void generatedAtomContainsUserAlertWithCharactersThatAreNotValidInXml() throws Exception {
		SimpleUserAlert userAlert = new SimpleUserAlert(true, "Alert <", "Contains a <.", "It’s <!", (short) 0);
		userAlertManager.register(userAlert);
		List<Node> nodes = getEntryNodes(createAtomXml());
		assertThat(nodes, contains(representsUserAlert(userAlert)));
	}

	@Test
	public void generatedAtomContainsMultipleUserAlerts() throws Exception {
		SimpleUserAlert firstAlert = new SimpleUserAlert(true, "Alert 1", "Text 1", "Short 1", (short) 0);
		SimpleUserAlert secondAlert = new SimpleUserAlert(true, "Alert 2", "Text 2", "Short 2", (short) 0);
		userAlertManager.register(secondAlert);
		userAlertManager.register(firstAlert);
		List<Node> entryNodes = getEntryNodes(createAtomXml());
		assertThat(entryNodes, containsInAnyOrder(
				representsUserAlert(firstAlert),
				representsUserAlert(secondAlert)
		));
	}

	@Test
	public void generatedAtomDoesNotContainInvalidUserAlerts() throws Exception {
		SimpleUserAlert firstAlert = new SimpleUserAlert(true, "Alert 1", "Text 1", "Short 1", (short) 0);
		SimpleUserAlert secondAlert = new SimpleUserAlert(true, "Alert 2", "Text 2", "Short 2", (short) 0) {
			@Override
			public boolean isValid() {
				return false;
			}
		};
		userAlertManager.register(secondAlert);
		userAlertManager.register(firstAlert);
		List<Node> entryNodes = getEntryNodes(createAtomXml());
		assertThat(entryNodes, containsInAnyOrder(representsUserAlert(firstAlert)));
	}

	@Test
	public void generatedAtomEntriesUseAnchorForId() throws Exception {
		SimpleUserAlert userAlert = new SimpleUserAlert(true, "Alert 1", "Text 1", "Short 1", (short) 0) {
			@Override
			public String anchor() {
				return "test-anchor";
			}
		};
		userAlertManager.register(userAlert);
		Node node = getEntryNodes(createAtomXml()).get(0);
		assertThat(xPath.evaluate("id", node), equalTo("urn:feed:test-anchor"));
	}

	private List<Node> getEntryNodes(Document atomXml) throws Exception {
		List<Node> nodes = new ArrayList<>();
		NodeList nodeList = (NodeList) xPath.evaluate("/feed/entry", atomXml, XPathConstants.NODESET);
		for (int index = 0; index < nodeList.getLength(); index++) {
			nodes.add(nodeList.item(index));
		}
		return nodes;
	}

	private Matcher<Node> representsUserAlert(UserAlert userAlert) {
		return new TypeSafeDiagnosingMatcher<Node>() {
			@Override
			protected boolean matchesSafely(Node node, Description mismatchDescription) {
				try {
					if (!Objects.equals(xPath.evaluate("id", node), "urn:feed:" + userAlert.anchor())) {
						mismatchDescription.appendText("id was ").appendValue(xPath.evaluate("id", node));
						return false;
					}
					if (!Objects.equals(xPath.evaluate("link/@href", node), "http://test/alerts/#" + userAlert.anchor())) {
						mismatchDescription.appendText("link was ").appendValue(xPath.evaluate("link/@href", node));
						return false;
					}
					if (!Objects.equals(xPath.evaluate("updated", node), formatTime(userAlert.getUpdatedTime()))) {
						mismatchDescription.appendText("updated was ").appendValue(xPath.evaluate("updated", node));
						return false;
					}
					if (!Objects.equals(xPath.evaluate("title", node), userAlert.getTitle())) {
						mismatchDescription.appendText("title was ").appendValue(xPath.evaluate("title", node));
						return false;
					}
					if (!Objects.equals(xPath.evaluate("summary", node), userAlert.getShortText())) {
						mismatchDescription.appendText("summary was ").appendValue(xPath.evaluate("summary", node));
						return false;
					}
					if (!Objects.equals(xPath.evaluate("content[@type='text']", node), userAlert.getText())) {
						mismatchDescription.appendText("content was ").appendValue(xPath.evaluate("content[@type='text']", node));
						return false;
					}
				} catch (XPathExpressionException e) {
					throw new RuntimeException(e);
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("is user alert ").appendValue(userAlert);
			}
		};
	}

	private static String formatTime(long time) {
		return String.format("%tY-%<tm-%<tdT%<tH:%<tM:%<tS%0+3d:%02d", time, MILLISECONDS.toHours(TimeZone.getDefault().getOffset(time)), MILLISECONDS.toMinutes(TimeZone.getDefault().getOffset(time)) % 60);
	}

	private void verifyStringPropertyInGeneratedAtom(String xpathExpression, Matcher<String> valueMatcher) throws Exception {
		verifyStringPropertyInAtom(createAtomXml(), xpathExpression, valueMatcher);
	}

	private void verifyStringPropertyInAtom(Document atomXml, String xpathExpression, Matcher<String> valueMatcher) throws Exception {
		String value = xPath.evaluate(xpathExpression, atomXml);
		assertThat(value, valueMatcher);
	}

	private Document createAtomXml() throws Exception {
		String atom = userAlertManager.getAtom("http://test");
		return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(atom)));
	}

	@Test
	public void renderingAnAlertWithAN2NTMRendersAReplyButton() {
		DarknetPeerNode darknetPeerNode = mock(DarknetPeerNode.class, RETURNS_DEEP_STUBS);
		when(darknetPeerNode.getWeakRef()).thenReturn(new WeakReference<>(mock(PeerNode.class)));
		UserAlert userAlert = new N2NTMUserAlert(darknetPeerNode, "", 0, 0, 0, 0);
		when(nodeClientCore.getFormPassword()).thenReturn("form-password");
		HTMLNode htmlNode = userAlertManager.renderAlert(userAlert);
		assertThat(htmlNode.generate(), isHtml(withElement("form[method='post'][action='/send_n2ntm/'] button[type='submit']")));
	}

	private final NodeClientCore nodeClientCore = mock(NodeClientCore.class, RETURNS_DEEP_STUBS);

	{
		when(nodeClientCore.getNode().getDarknetPubKeyHash()).thenReturn(new byte[] { 1, 2, 3, 4 });
	}

	private final UserAlertManager userAlertManager = new UserAlertManager(nodeClientCore);
	private static final XPath xPath = XPathFactory.newInstance().newXPath();

}
