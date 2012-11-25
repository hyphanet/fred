package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Asks the user whether their connection has a monthly cap to inform how to prompt for bandwidth limits.
 */
public class BANDWIDTH implements Step {

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("step3Title"));

		HTMLNode bandwidthInfoboxContent = helper.getInfobox("infobox-normal", WizardL10n.l10n("bandwidthLimit"),
		        contentNode, null, false);

		bandwidthInfoboxContent.addChild("#", WizardL10n.l10n("bandwidthCapPrompt"));
		HTMLNode bandwidthForm = helper.addFormChild(bandwidthInfoboxContent, ".", "bwForm");
		bandwidthForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "yes", NodeL10n.getBase().getString("Toadlet.yes")});
		bandwidthForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "no", NodeL10n.getBase().getString("Toadlet.no")});
		bandwidthForm.addChild("div").addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "back", NodeL10n.getBase().getString("Toadlet.back")});
	}

	@Override
	public String postStep(HTTPRequest request)  {

		//Yes: Set for monthly data limit.
		if (request.isPartSet("yes")) return FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH_MONTHLY.name();

		//No: Set for data rate limit. 
		/*else if (request.isPartSet("no"))*/ return FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH_RATE.name();

		//Back: FirstTimeWizardToadlet handles that.
	}
}
