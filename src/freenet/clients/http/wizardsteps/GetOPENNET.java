package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.clients.http.PageNode;
import freenet.clients.http.ToadletContext;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * This step allows the user to choose between darknet and opennet, explaining each briefly.
 */
public class GetOPENNET extends AbstractGetStep {

	@Override
	public String getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

		infoboxHeader.addChild("#", l10n("opennetChoiceTitle"));

		infoboxContent.addChild("p", l10n("opennetChoiceIntroduction"));

		HTMLNode form = infoboxContent.addChild("form", new String[] { "action", "method", "id" }, new String[] { "GET", ".", "opennetChoiceForm" });
		form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "step", FirstTimeWizardToadlet.WIZARD_STEP.SECURITY_NETWORK.name() });

		HTMLNode p = form.addChild("p");
		HTMLNode input = p.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "opennet", "false" });
		input.addChild("b", l10n("opennetChoiceConnectFriends")+":");
		p.addChild("br");
		p.addChild("i", l10n("opennetChoicePro"));
		p.addChild("#", ": "+l10n("opennetChoiceConnectFriendsPRO") + "¹");
		p.addChild("br");
		p.addChild("i", l10n("opennetChoiceCon"));
		p.addChild("#", ": "+l10n("opennetChoiceConnectFriendsCON", "minfriends", "5"));

		p = form.addChild("p");
		input = p.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "opennet", "true" });
		input.addChild("b", l10n("opennetChoiceConnectStrangers")+":");
		p.addChild("br");
		p.addChild("i", l10n("opennetChoicePro"));
		p.addChild("#", ": "+l10n("opennetChoiceConnectStrangersPRO"));
		p.addChild("br");
		p.addChild("i", l10n("opennetChoiceCon"));
		p.addChild("#", ": "+l10n("opennetChoiceConnectStrangersCON"));

		form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "opennetF", l10n("continue")});
		form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
		HTMLNode foot = infoboxContent.addChild("div", "class", "toggleable");
		foot.addChild("i", "¹: " + l10n("opennetChoiceHowSafeIsFreenetToggle"));
		HTMLNode footHidden = foot.addChild("div", "class", "hidden");
		HTMLNode footList = footHidden.addChild("ol");
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetStupid"));
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetFriends") + "²");
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetTrustworthy"));
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetNoSuspect"));
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetChangeID"));
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetSSK"));
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetOS"));
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetBigPriv"));
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetDistant"));
		footList.addChild("li", l10n("opennetChoiceHowSafeIsFreenetBugs"));
		HTMLNode foot2 = footHidden.addChild("p");
		foot2.addChild("#", "²: " + l10n("opennetChoiceHowSafeIsFreenetFoot2"));

		return contentNode.generate();
	}
}
