package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.clients.http.PageMaker.RenderParameters;
import freenet.clients.http.PageNode;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;

/**
 * Provides a page content node, forms, and InfoBoxes. Used to wrap ToadletContext access away from Wizard Steps.
 * A new one should be constructed each time a step is run.
 */
public class PageHelper {

	private final ToadletContext toadletContext;
	private final PersistFields persistFields;
	private final FirstTimeWizardToadlet.WIZARD_STEP step;
	private PageNode pageNode;

	public PageHelper(ToadletContext ctx, PersistFields persistFields, FirstTimeWizardToadlet.WIZARD_STEP step) {
		this.toadletContext = ctx;
		this.persistFields = persistFields;
		this.step = step;
	}

	/**
	 * Generates a PageMaker with the appropriate arguments for the wizard. (Ex hiding status and nav bars)
	 * This is so that steps can determine their page title at runtime instead of being limited to one. This is
	 * needed for the physical security page.
	 * @param title desired page title
	 * @return Content HTMLNode to add content to
	 */
	public HTMLNode getPageContent(String title) {
		pageNode = toadletContext.getPageMaker().getPageNode(title, toadletContext, new RenderParameters().renderNavigationLinks(false).renderStatus(false));
		return pageNode.content;
	}

	/**
	 * After getPageContent has been called, returns page outer HTMLNode.
	 * @return page outer node used to render entire page.
	 */
	public HTMLNode getPageOuter() {
		if (pageNode == null) {
			throw new NullPointerException("pageNode was not initialized. getPageContent must be called first.");
		}
		return pageNode.outer;
	}

	public HTMLNode getInfobox(String category, String header, HTMLNode parent, String title, boolean isUnique) {
		return toadletContext.getPageMaker().getInfobox(category, header, parent, title, isUnique);
	}

	public HTMLNode addFormChild(HTMLNode parentNode, String target, String id) {
		return addFormChild(parentNode, target, id, true);
	}

	/**
	 * Generates a form that includes persistence for inter-step fields. This is currently opennet, preset, and step.
	 * Opennet is whether the user enabled opennet, preset is what preset they're using, and step is what POST step
	 * will be used to process the form.
	 * @param parentNode node to add form to
	 * @param target where form should POST to
	 * @param id ID attribute (in HTML) of form
	 * @param includeOpennet whether the opennet field should be persisted. False on the OPENNET step.
	 * @return form node to add buttons, inputs, and whatnot to.
	 */
	public HTMLNode addFormChild(HTMLNode parentNode, String target, String id, boolean includeOpennet) {
		HTMLNode form = toadletContext.addFormChild(parentNode, target, id);
		if (persistFields.isUsingPreset()) {
			form.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "hidden", "preset", persistFields.preset.name() });
		}
		if (includeOpennet) {
			form.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "hidden", "opennet", String.valueOf(persistFields.opennet) });
		}
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "hidden", "step", step.name() });
		return form;
	}
}