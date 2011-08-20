package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * Allows the user to choose a node name for Darknet.
 */
public class NAME_SELECTION implements Step {

	private final Config config;

	public NAME_SELECTION(Config config) {
		this.config = config;
	}

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("step2Title"));
		HTMLNode nnameInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode nnameInfoboxHeader = nnameInfobox.addChild("div", "class", "infobox-header");
		HTMLNode nnameInfoboxContent = nnameInfobox.addChild("div", "class", "infobox-content");

		nnameInfoboxHeader.addChild("#", WizardL10n.l10n("chooseNodeName"));
		nnameInfoboxContent.addChild("#", WizardL10n.l10n("chooseNodeNameLong"));
		HTMLNode nnameForm = helper.addFormChild(nnameInfoboxContent, ".", "nnameForm");
		nnameForm.addChild("input", "name", "nname");

		nnameForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "nnameF", WizardL10n.l10n("continue")});
		nnameForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
	}

	@Override
	public String postStep(HTTPRequest request) {
		String selectedNName = request.getPartAsStringFailsafe("nname", 128);
		try {
			//User chose cancel
			if (request.isParameterSet("cancel")) {
				//Refresh page if no name given
				return FirstTimeWizardToadlet.TOADLET_URL+"?step="+ FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH;
			}
			config.get("node").set("name", selectedNName);
			Logger.normal(this, "The node name has been set to " + selectedNName);
		} catch (ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
		return FirstTimeWizardToadlet.TOADLET_URL+"?step="+ FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH;
	}
}
