package freenet.clients.http.utils;

import freenet.l10n.BaseL10n;
import freenet.l10n.BaseL10nTest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import freenet.support.HTMLNode;
import org.junit.Test;

import static freenet.l10n.BaseL10n.LANGUAGE.ENGLISH;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class PebbleUtilsTest {

	@Test
	public void addChildAddsCorrectlyEvaluatedSimpleTemplateToHtmlNode() throws IOException {
		PebbleUtils.addChild(emptyParentNode, "pebble-utils-test-simple", model, null);
		assertThat(emptyParentNode.generate(), equalTo("Test!\n"));
	}

	@Test
	public void addChildAddsCorrectlyEvaluatedTemplateWithL10nFunctionToHtmlNode() throws IOException {
		PebbleUtils.addChild(emptyParentNode, "pebble-utils-test-l10n", model, "pebble-utils-tests.");
		assertThat(emptyParentNode.generate(), equalTo("Test Value"));
	}

	private final HTMLNode emptyParentNode = new HTMLNode("#");
	private final Map<String, Object> model = new HashMap<>();

	static {
		PebbleUtils.setBaseL10n(BaseL10nTest.createTestL10n(ENGLISH));
	}

}
