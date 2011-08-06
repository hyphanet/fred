package freenet.clients.http.wizardsteps;

import freenet.clients.http.ToadletContext;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Allows the user to choose a node name for Darknet.
 */
public class GetNAME_SELECTION implements GetStep {

	@Override
	public String getTitleKey() {
		return "step2Title";
	}

	@Override
	public String getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode nnameInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode nnameInfoboxHeader = nnameInfobox.addChild("div", "class", "infobox-header");
		HTMLNode nnameInfoboxContent = nnameInfobox.addChild("div", "class", "infobox-content");

		nnameInfoboxHeader.addChild("#", WizardL10n.l10n("chooseNodeName"));
		nnameInfoboxContent.addChild("#", WizardL10n.l10n("chooseNodeNameLong"));
		HTMLNode nnameForm = ctx.addFormChild(nnameInfoboxContent, ".", "nnameForm");
		nnameForm.addChild("input", "name", "nname");

		nnameForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "nnameF", WizardL10n.l10n("continue")});
		nnameForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});

		return contentNode.generate();
	}
}
