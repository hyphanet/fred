package freenet.clients.http.wizardsteps;

import freenet.clients.http.ConfigToadlet;
import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.clients.http.ToadletContext;
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
		HTMLNode welcomeInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode welcomeInfoboxHeader = welcomeInfobox.addChild("div", "class", "infobox-header");
		HTMLNode welcomeInfoboxContent = welcomeInfobox.addChild("div", "class", "infobox-content");
		welcomeInfoboxHeader.addChild("#", WizardL10n.l10n("welcomeInfoboxTitle"));

		HTMLNode firstParagraph = welcomeInfoboxContent.addChild("p");
		firstParagraph.addChild("#", WizardL10n.l10n("welcomeInfoboxContent1"));

		HTMLNode secondParagraph = welcomeInfoboxContent.addChild("p");
		StringBuilder continueTo = new StringBuilder("?step=").
		        append(FirstTimeWizardToadlet.WIZARD_STEP.BROWSER_WARNING);
		if (request.isParameterSet("incognito")) {
			continueTo.append("&incognito=true");
		}
		secondParagraph.addChild("a", "href", continueTo.toString()).addChild("#", WizardL10n.l10n("clickContinue"));

		HTMLNode languageForm = ctx.addFormChild(secondParagraph, ".", "languageForm");
		//Marker for step on POST side
		languageForm.addChild("input",
		        new String [] { "type", "name", "value" },
		        new String [] { "hidden", "step", "WELCOME"});
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
	public void postStep(HTTPRequest request, ToadletContext ctx) {
		//The user changed their language on the welcome page. Change the language and rerender the page
		//by falling through to the invalid redirect.
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
	}
}
