package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * This step allows the user to choose between darknet and opennet, explaining each briefly.
 */
public class OPENNET implements Step {

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("opennetChoicePageTitle"));
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

		infoboxHeader.addChild("#", WizardL10n.l10n("opennetChoiceTitle"));

		infoboxContent.addChild("p", WizardL10n.l10n("opennetChoiceIntroduction"));

		HTMLNode form = helper.addFormChild(infoboxContent, ".", "opennetForm");

		HTMLNode p = form.addChild("p");
		HTMLNode input = p.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "radio", "opennet", "false" });
		input.addChild("b", WizardL10n.l10n("opennetChoiceConnectFriends")+":");
		p.addChild("br");
		p.addChild("i", WizardL10n.l10n("opennetChoicePro"));
		p.addChild("#", ": "+WizardL10n.l10n("opennetChoiceConnectFriendsPRO") + "¹");
		p.addChild("br");
		p.addChild("i", WizardL10n.l10n("opennetChoiceCon"));
		p.addChild("#", ": "+WizardL10n.l10n("opennetChoiceConnectFriendsCON", "minfriends", "5"));

		p = form.addChild("p");
		input = p.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "radio", "opennet", "true" });
		input.addChild("b", WizardL10n.l10n("opennetChoiceConnectStrangers")+":");
		p.addChild("br");
		p.addChild("i", WizardL10n.l10n("opennetChoicePro"));
		p.addChild("#", ": "+WizardL10n.l10n("opennetChoiceConnectStrangersPRO"));
		p.addChild("br");
		p.addChild("i", WizardL10n.l10n("opennetChoiceCon"));
		p.addChild("#", ": "+WizardL10n.l10n("opennetChoiceConnectStrangersCON"));

		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "opennetF", WizardL10n.l10n("continue")});
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
		HTMLNode foot = infoboxContent.addChild("div", "class", "toggleable");
		foot.addChild("i", "¹: " + WizardL10n.l10n("opennetChoiceHowSafeIsFreenetToggle"));
		HTMLNode footHidden = foot.addChild("div", "class", "hidden");
		HTMLNode footList = footHidden.addChild("ol");
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetStupid"));
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetFriends") + "²");
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetTrustworthy"));
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetNoSuspect"));
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetChangeID"));
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetSSK"));
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetOS"));
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetBigPriv"));
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetDistant"));
		footList.addChild("li", WizardL10n.l10n("opennetChoiceHowSafeIsFreenetBugs"));
		HTMLNode foot2 = footHidden.addChild("p");
		foot2.addChild("#", "²: " + WizardL10n.l10n("opennetChoiceHowSafeIsFreenetFoot2"));
	}

	/**
	 * Doesn't make any changes, just passes result on to SECURITY_NETWORK.
	 * @param request Checked for "opennet" value.
	 */
	@Override
	public String postStep(HTTPRequest request) {
		if (request.isPartSet("opennet")) {
			return FirstTimeWizardToadlet.TOADLET_URL+"?step=SECURITY_NETWORK&opennet="+request.getPartAsStringFailsafe("opennet", 5);
		} else {
			//Nothing selected when continue clicked. Display choice again.
			return FirstTimeWizardToadlet.TOADLET_URL+"?step=OPENNET";
		}
	}
}
