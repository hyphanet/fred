package freenet.clients.http.wizardsteps;

import freenet.clients.http.PageNode;
import freenet.clients.http.ToadletContext;
import freenet.config.Config;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * An AbstractGetStep returns a rendered HTMLNode to return to the browser.
 */
public abstract class AbstractGetStep {

	/**
	 * Localization key for the page title.
	 */
	public final String TITLE_KEY = "unset";

	/**
	 * Renders a page for a step in the wizard.
	 * @param contentNode page content node to render content into.
	 * @param ctx For adding forms and getting a PageNode.
	 * @param request The HTTPRequest for the page, used in its creation.
	 * @return HTML to be sent to the browser.
	 */
	public abstract String getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx);

	protected String l10n(String key) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key);
	}

	protected String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key, pattern, value);
	}

	protected String l10nSec(String key) {
		return NodeL10n.getBase().getString("SecurityLevels."+key);
	}
}
