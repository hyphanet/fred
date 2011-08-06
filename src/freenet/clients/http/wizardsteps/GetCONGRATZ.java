package freenet.clients.http.wizardsteps;

import freenet.clients.http.ToadletContext;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * Wizard completion page. Sets completion in config and links to the node's main page.
 */
public class GetCONGRATZ implements GetStep {

	private final Config config;

	public GetCONGRATZ(Config config) {
		this.config = config;
	}

	@Override
	public String getTitleKey() {
		return "step7Title";
	}

	@Override
	public void getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		//Set wizard completion flag
		try {
			config.get("fproxy").set("hasCompletedWizard", true);
			config.store();
		} catch (ConfigException e) {
			//TODO: Is there anything that can reasonably be done about this? What kind of failures could occur?
			Logger.error(this, e.getMessage(), e);
		}

		HTMLNode congratzInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode congratzInfoboxHeader = congratzInfobox.addChild("div", "class", "infobox-header");
		HTMLNode congratzInfoboxContent = congratzInfobox.addChild("div", "class", "infobox-content");

		congratzInfoboxHeader.addChild("#", WizardL10n.l10n("congratz"));
		congratzInfoboxContent.addChild("p", WizardL10n.l10n("congratzLong"));

		congratzInfoboxContent.addChild("a", "href", "/", WizardL10n.l10n("continueEnd"));
	}
}
