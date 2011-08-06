package freenet.clients.http.wizardsteps;

import com.db4o.foundation.ArgumentNullException;
import freenet.clients.http.ConfigToadlet;
import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.clients.http.ToadletContext;
import freenet.config.Config;
import freenet.config.EnumerableOptionCallback;
import freenet.config.Option;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * This step is the first, and provides a small welcome screen and an option to change the language.
 */
public class GetWELCOME extends AbstractGetStep {

	public static final String TITLE_KEY = "homepageTitle";

	private final Config config;

	/**
	 * Constructs a new WELCOME GET handler.
	 * @param config Node config; cannot be null. Used to build language drop-down.
	 */
	public GetWELCOME(Config config) {
		this.config = config;
	}

	/**
	 * Renders the first page of the wizard.
	 * @param request used to check whether the user is using a browser with incognito mode.
	 * @param ctx used to add the language selection drop-down form and get the PageNode.
	 * @return HTML of the first page.
	 */
	@Override
	public String getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode welcomeInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode welcomeInfoboxHeader = welcomeInfobox.addChild("div", "class", "infobox-header");
		HTMLNode welcomeInfoboxContent = welcomeInfobox.addChild("div", "class", "infobox-content");
		welcomeInfoboxHeader.addChild("#", l10n("welcomeInfoboxTitle"));

		HTMLNode firstParagraph = welcomeInfoboxContent.addChild("p");
		firstParagraph.addChild("#", l10n("welcomeInfoboxContent1"));

		HTMLNode secondParagraph = welcomeInfoboxContent.addChild("p");
		StringBuilder continueTo = new StringBuilder("?step=").
		        append(FirstTimeWizardToadlet.WIZARD_STEP.BROWSER_WARNING);
		if (request.isParameterSet("incognito")) {
			continueTo.append("&incognito=true");
		}
		secondParagraph.addChild("a", "href", continueTo.toString()).addChild("#", l10n("clickContinue"));

		HTMLNode languageForm = ctx.addFormChild(secondParagraph, ".", "languageForm");
		Option language = config.get("node").getOption("l10n");
		EnumerableOptionCallback l10nCallback = (EnumerableOptionCallback)language.getCallback();
		HTMLNode dropDown = ConfigToadlet.addComboBox(language.getValueString(), l10nCallback, language.getName(), false);
		//Submit automatically upon selection if Javascript.
		dropDown.addAttribute("onchange", "this.form.submit()");
		languageForm.addChild(dropDown);
		//Otherwise fall back to submit button if no Javascript
		languageForm.addChild("noscript").addChild("input", "type", "submit");

		return contentNode.generate();
	}
}
