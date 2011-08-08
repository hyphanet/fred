package freenet.clients.http.wizardsteps;

import freenet.clients.http.*;
import freenet.config.Config;
import freenet.config.EnumerableOptionCallback;
import freenet.config.Option;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * This step is the first, and provides a small welcome screen and an option to change the language.
 */
public class WELCOME implements Step {

	private final Config config;

	/**
	 * Constructs a new WELCOME GET handler.
	 * @param config Node config; cannot be null. Used to build language drop-down.
	 */
	public WELCOME(Config config) {
		this.config = config;
	}

	@Override
	public String getTitleKey() {
		return "homepageTitle";
	}

	/**
	 * Renders the first page of the wizard into the given content node.
	 * @param request used to check whether the user is using a browser with incognito mode.
	 * @param ctx used to add the language selection drop-down form and get the PageNode.
	 */
	@Override
	public void getStep(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		PageMaker pm = ctx.getPageMaker();
		HTMLNode welcomeInfoboxContent = addInfoBox(WizardL10n.l10n("welcomeInfoboxTitle"), pm, contentNode);
		welcomeInfoboxContent.addChild("p", WizardL10n.l10n("welcomeInfoboxContent1"));

		boolean incognito = request.isParameterSet("incognito");

		//Low security option
		addSecurityBox(contentNode, pm, "Low", ctx, incognito);

		//High security option
		addSecurityBox(contentNode, pm, "High", ctx, incognito);

		//Detailed wizard option
		addSecurityBox(contentNode, pm, "None", ctx, incognito);

		HTMLNode languageForm = ctx.addFormChild(contentNode, ".", "languageForm");
		//Marker for step on POST side
		languageForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "hidden", "step", "WELCOME"});
		//Add option dropdown for languages
		Option language = config.get("node").getOption("l10n");
		EnumerableOptionCallback l10nCallback = (EnumerableOptionCallback)language.getCallback();
		HTMLNode dropDown = ConfigToadlet.addComboBox(language.getValueString(), l10nCallback, language.getName(), false);
		//Submit automatically upon selection if Javascript.
		dropDown.addAttribute("onchange", "this.form.submit()");
		languageForm.addChild(dropDown);
		//Otherwise fall back to submit button if no Javascript
		languageForm.addChild("noscript").addChild("input", "type", "submit");
	}

	@Override
	public String postStep(HTTPRequest request, ToadletContext ctx) {
		//The user changed their language on the welcome page. Change the language and re-render the page.
		//Presets are handled within FirstTimeWizardToadlet because it can access all steps.
		String desiredLanguage = request.getPartAsStringFailsafe("l10n", 4096);
		try {
			config.get("node").set("l10n", desiredLanguage);
		} catch (freenet.config.InvalidConfigValueException e) {
			Logger.error(this, "Failed to set language to " + desiredLanguage + ". " + e);
		} catch (freenet.config.NodeNeedRestartException e) {
			//Changing language doesn't require a restart, at least as of version 1385.
			//Doing so would be really annoying as the node would have to start up again
			//which could be very slow.
		}
		return FirstTimeWizardToadlet.TOADLET_URL;
	}

	/**
	 * Adds an InfoBox with the given title to the parent HTMLNode. Idea credit to p0s/x0r in Freetalk.
	 * @param infoBoxTitle The title of the InfoBox.
	 * @param pm The pageMaker to get the InfoBox from.
	 * @param parent Parent HTMLNode to add the InfoBox to.
	 * @return HTMLNode of the InfoBox's content.
	 */
	private HTMLNode addInfoBox(String infoBoxTitle, PageMaker pm, HTMLNode parent) {
		InfoboxNode invitationsBox = pm.getInfobox(infoBoxTitle);
		parent.addChild(invitationsBox.outer);
		return invitationsBox.content;
	}

	/**
	 * Adds an infobox with information about a given security level and button.
	 * @param parent node to add infobox to.
	 * @param pm to get the InfoBox from.
	 * @param preset suffix for security level keys.
	 * @param ctx used to add a form
	 * @param incognito whether incognito mode is enabled
	 */
	private void addSecurityBox(HTMLNode parent, PageMaker pm, String preset, ToadletContext ctx, boolean incognito) {
		HTMLNode securityContent = addInfoBox(WizardL10n.l10n("presetTitle"+preset), pm, parent);
		securityContent.addChild("p", WizardL10n.l10n("preset" + preset));
		HTMLNode secForm = ctx.addFormChild(securityContent, ".", "SecForm"+preset);
		secForm.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"hidden", "incognito", String.valueOf(incognito)});
		secForm.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"submit", "preset" + preset, WizardL10n.l10n("presetChoose" + preset)});
	}
}
