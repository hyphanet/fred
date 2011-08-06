package freenet.clients.http.wizardsteps;

import freenet.clients.http.ToadletContext;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Allows the user to choose whether to enable auto-updating.
 */
public class GetMISC extends AbstractGetStep {

	public static final String TITLE_KEY = "stepMiscTitle";

	@Override
	public String getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode form = ctx.addFormChild(contentNode, ".", "miscForm");

		HTMLNode miscInfobox = form.addChild("div", "class", "infobox infobox-normal");
		HTMLNode miscInfoboxHeader = miscInfobox.addChild("div", "class", "infobox-header");
		HTMLNode miscInfoboxContent = miscInfobox.addChild("div", "class", "infobox-content");

		miscInfoboxHeader.addChild("#", l10n("autoUpdate"));
		miscInfoboxContent.addChild("p", l10n("autoUpdateLong"));
		miscInfoboxContent.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" },
				new String[] { "radio", "on", "autodeploy", "true" }, l10n("autoUpdateAutodeploy"));
		miscInfoboxContent.addChild("p").addChild("input", new String[] { "type", "name", "value" },
				new String[] { "radio", "autodeploy", "false" }, l10n("autoUpdateNoAutodeploy"));

		miscInfobox = form.addChild("div", "class", "infobox infobox-normal");
		miscInfoboxHeader = miscInfobox.addChild("div", "class", "infobox-header");
		miscInfoboxContent = miscInfobox.addChild("div", "class", "infobox-content");

		miscInfoboxHeader.addChild("#", l10n("plugins"));
		miscInfoboxContent.addChild("p", l10n("pluginsLong"));
		miscInfoboxContent.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" },
				new String[] { "checkbox", "on", "upnp", "true" }, l10n("enableUPnP"));

		miscInfoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "miscF", l10n("continue")});
		miscInfoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});

		return contentNode.generate();
	}
}
