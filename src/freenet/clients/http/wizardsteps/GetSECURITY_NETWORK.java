package freenet.clients.http.wizardsteps;

import freenet.clients.http.ToadletContext;
import freenet.l10n.NodeL10n;
import freenet.node.SecurityLevels;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * This step allows the user to choose between security levels. If opennet is disabled, only high and maximum are shown.
 */
public class GetSECURITY_NETWORK implements GetStep {

	@Override
	public String getTitleKey() {
		return "networkSecurityPageTitle";
	}

	public void getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

		//If opennet isn't defined, re-ask.
		if(!request.isParameterSet("opennet")) {
			GetOPENNET reRender = new GetOPENNET();
			reRender.getPage(contentNode, request, ctx);
			return;
		}

		String opennetParam = request.getParam("opennet", "false");
		boolean opennet = Fields.stringToBool(opennetParam);

		infoboxHeader.addChild("#", WizardL10n.l10n(opennet ? "networkThreatLevelHeaderOpennet" : "networkThreatLevelHeaderDarknet"));
		infoboxContent.addChild("p", WizardL10n.l10n(opennet ? "networkThreatLevelIntroOpennet" : "networkThreatLevelIntroDarknet"));
		HTMLNode form = ctx.addFormChild(infoboxContent, ".", "networkSecurityForm");
		form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "opennet", opennetParam });
		String controlName = "security-levels.networkThreatLevel";
		if(opennet) {
			HTMLNode div = form.addChild("div", "class", "opennetDiv");
			for(SecurityLevels.NETWORK_THREAT_LEVEL level : SecurityLevels.NETWORK_THREAT_LEVEL.OPENNET_VALUES) {
				HTMLNode input;
				input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
				input.addChild("b", WizardL10n.l10nSec("networkThreatLevel.name."+level));
				input.addChild("#", ": ");
				NodeL10n.getBase().addL10nSubstitution(input, "SecurityLevels.networkThreatLevel.choice."+level, new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
				HTMLNode inner = input.addChild("p").addChild("i");
				NodeL10n.getBase().addL10nSubstitution(inner, "SecurityLevels.networkThreatLevel.desc."+level, new String[] { "bold" },
						new HTMLNode[] { HTMLNode.STRONG });
			}
		}
		if(!opennet) {
			HTMLNode div = form.addChild("div", "class", "darknetDiv");
			for(SecurityLevels.NETWORK_THREAT_LEVEL level : SecurityLevels.NETWORK_THREAT_LEVEL.DARKNET_VALUES) {
				HTMLNode input;
				input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
				input.addChild("b", WizardL10n.l10nSec("networkThreatLevel.name."+level));
				input.addChild("#", ": ");
				NodeL10n.getBase().addL10nSubstitution(input, "SecurityLevels.networkThreatLevel.choice."+level, new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
				HTMLNode inner = input.addChild("p").addChild("i");
				NodeL10n.getBase().addL10nSubstitution(inner, "SecurityLevels.networkThreatLevel.desc."+level, new String[] { "bold" },
						new HTMLNode[] { HTMLNode.STRONG });
			}
			form.addChild("p").addChild("b", WizardL10n.l10nSec("networkThreatLevel.opennetFriendsWarning"));
		}
		form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "networkSecurityF", WizardL10n.l10n("continue")});
		form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
	}
}
