package freenet.clients.http.wizardsteps;

import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

import java.io.IOException;

/**
 * Wizard completion page. Sets completion in config and links to the node's main page.
 */
public class CONGRATZ implements Step {

	private final Config config;

	public CONGRATZ(Config config) {
		this.config = config;
	}

	@Override
	public void getStep(HTTPRequest request, StepPageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("step7Title"));
		HTMLNode congratzInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode congratzInfoboxHeader = congratzInfobox.addChild("div", "class", "infobox-header");
		HTMLNode congratzInfoboxContent = congratzInfobox.addChild("div", "class", "infobox-content");

		congratzInfoboxHeader.addChild("#", WizardL10n.l10n("congratz"));
		congratzInfoboxContent.addChild("p", WizardL10n.l10n("congratzLong"));

		HTMLNode continueForm = helper.addFormChild(congratzInfoboxContent, ".", "continueForm");
		continueForm.addChild("input",
		        new String [] { "type", "name", "value" },
		        new String [] { "hidden", "step", "CONGRATZ" });
		continueForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "CONGRATZ", WizardL10n.l10n("continueEnd")});
	}

	@Override
	public String postStep(HTTPRequest request) {
		//Set wizard completion flag
		try {
			config.get("fproxy").set("hasCompletedWizard", true);
			config.store();
		} catch (ConfigException e) {
			//TODO: Is there anything that can reasonably be done about this? What kind of failures could occur?
			//TODO: Is log an continue a reasonable behavior?
			Logger.error(this, e.getMessage(), e);
		}
		return "/";
	}
}
