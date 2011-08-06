package freenet.clients.http.wizardsteps;

import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Classes which implement GetStep return HTML for the step to be sent to the browser.
 */
public interface GetStep {

	/**
	 * @return Localization key for the page title.
	 */
	public String getTitleKey();

	/**
	 * Renders a page for a step in the wizard by modifying contentNode.
	 * @param contentNode page content node to render content into.
	 * @param ctx For adding forms and getting a PageNode.
	 * @param request The HTTPRequest for the page, used in its creation.
	 */
	public void getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx);
}
