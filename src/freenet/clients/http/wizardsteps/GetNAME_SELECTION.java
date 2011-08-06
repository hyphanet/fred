package freenet.clients.http.wizardsteps;

import freenet.clients.http.PageNode;
import freenet.clients.http.ToadletContext;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Allows the user to choose a node name for Darknet.
 */
public class GetNAME_SELECTION extends AbstractGetStep {

	public static final String TITLE_KEY = "step2Title";

	@Override
	public String getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode nnameInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode nnameInfoboxHeader = nnameInfobox.addChild("div", "class", "infobox-header");
		HTMLNode nnameInfoboxContent = nnameInfobox.addChild("div", "class", "infobox-content");

		nnameInfoboxHeader.addChild("#", l10n("chooseNodeName"));
		nnameInfoboxContent.addChild("#", l10n("chooseNodeNameLong"));
		HTMLNode nnameForm = ctx.addFormChild(nnameInfoboxContent, ".", "nnameForm");
		nnameForm.addChild("input", "name", "nname");

		nnameForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "nnameF", l10n("continue")});
		nnameForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});

		return contentNode.generate();
	}
}
