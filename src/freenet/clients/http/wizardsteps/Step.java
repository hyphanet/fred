package freenet.clients.http.wizardsteps;

import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

import java.io.IOException;

/**
 * Classes which implement Step are sub-Toadlets accessible only through the wizard, and are not registered in FProxy.
 */
public interface Step {

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
	public void getStep(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx);

	/**
	 * Performs operations for the step.
	 * @param request Parameters to inform the step.
	 * @param ctx Used to write redirects.
	 * @throws IOException if Toadlet's WriteTemporaryRedirect does.
	 * @throws ToadletContextClosedException if Toadlet's WriteTemporaryRedirect does.
	 * @return a destination to redirect to, or null if no redirect is wanted. (Ex. the POST wrote HTML)
	 */
	public String postStep(HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException;
}
