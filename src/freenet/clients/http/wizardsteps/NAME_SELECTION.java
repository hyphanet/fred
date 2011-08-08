package freenet.clients.http.wizardsteps;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

import java.io.IOException;

/**
 * Allows the user to choose a node name for Darknet.
 */
public class NAME_SELECTION implements Step {

	private final Config config;

	public NAME_SELECTION(Config config, HighLevelSimpleClient client) {
		this.config = config;
	}

	@Override
	public String getTitleKey() {
		return "step2Title";
	}

	@Override
	public void getStep(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode nnameInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode nnameInfoboxHeader = nnameInfobox.addChild("div", "class", "infobox-header");
		HTMLNode nnameInfoboxContent = nnameInfobox.addChild("div", "class", "infobox-content");

		nnameInfoboxHeader.addChild("#", WizardL10n.l10n("chooseNodeName"));
		nnameInfoboxContent.addChild("#", WizardL10n.l10n("chooseNodeNameLong"));
		HTMLNode nnameForm = ctx.addFormChild(nnameInfoboxContent, ".", "nnameForm");
		nnameForm.addChild("input", "name", "nname");

		//Marker for step on POST side
		nnameForm.addChild("input",
		        new String [] { "type", "name", "value"},
		        new String [] { "hidden", "step", "NAME_SELECTION" });
		nnameForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "nnameF", WizardL10n.l10n("continue")});
		nnameForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
	}

	@Override
	public String postStep(HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String selectedNName = request.getPartAsStringFailsafe("nname", 128);
		try {
			config.get("node").set("name", selectedNName);
			Logger.normal(this, "The node name has been set to " + selectedNName);
		} catch (ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
		return FirstTimeWizardToadlet.TOADLET_URL+"?step="+ FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH;
	}
}
