package freenet.clients.http.wizardsteps;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.*;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

import java.io.IOException;

/**
 * This step allows the user to choose between security levels. If opennet is disabled, only high and maximum are shown.
 * If opennet is enabled, only low and normal are shown.
 */
public class SECURITY_NETWORK extends Toadlet implements Step {

	private final NodeClientCore core;

	public SECURITY_NETWORK(NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.core = core;
	}

	@Override
	public String path() {
		return FirstTimeWizardToadlet.TOADLET_URL+"?step=SECURITY_NETWORK";
	}

	@Override
	public String getTitleKey() {
		return "networkSecurityPageTitle";
	}

	public void getStep(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

		String opennetParam = request.getParam("opennet", "false");
		boolean opennet = Fields.stringToBool(opennetParam);

		//Add choices and description depending on whether opennet was selected.
		HTMLNode form = ctx.addFormChild(infoboxContent, ".", "networkSecurityForm");
		form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "opennet", opennetParam });
		if(opennet) {
			infoboxHeader.addChild("#", WizardL10n.l10n("networkThreatLevelHeaderOpennet"));
			infoboxContent.addChild("p", WizardL10n.l10n("networkThreatLevelIntroOpennet"));
			HTMLNode div = form.addChild("div", "class", "opennetDiv");
			for(SecurityLevels.NETWORK_THREAT_LEVEL level : SecurityLevels.NETWORK_THREAT_LEVEL.OPENNET_VALUES) {
				securityLevelChoice(div, level);
			}
		} else {
			infoboxContent.addChild("p", WizardL10n.l10n("networkThreatLevelIntroDarknet"));
			infoboxHeader.addChild("#", WizardL10n.l10n("networkThreatLevelHeaderDarknet"));
			HTMLNode div = form.addChild("div", "class", "darknetDiv");
			for(SecurityLevels.NETWORK_THREAT_LEVEL level : SecurityLevels.NETWORK_THREAT_LEVEL.DARKNET_VALUES) {
				securityLevelChoice(div, level);
			}
			form.addChild("p").addChild("b", WizardL10n.l10nSec("networkThreatLevel.opennetFriendsWarning"));
		}
		//Marker for step on POST side
		form.addChild("input",
		        new String [] { "type", "name", "value" },
		        new String [] { "hidden", "step", "SECURITY_NETWORK" });
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "networkSecurityF", WizardL10n.l10n("continue")});
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
	}

	/**
	 * Adds to the given parent node description and a radio button for the selected security level.
	 * @param parent to add content to.
	 * @param level to add content about.
	 */
	private void securityLevelChoice(HTMLNode parent, SecurityLevels.NETWORK_THREAT_LEVEL level) {
		HTMLNode input = parent.addChild("p").addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "radio", "security-levels.networkThreatLevel", level.name() });
		input.addChild("b", WizardL10n.l10nSec("networkThreatLevel.name."+level));
		input.addChild("#", ": ");
		NodeL10n.getBase().addL10nSubstitution(input, "SecurityLevels.networkThreatLevel.choice."+level,
		        new String[] { "bold" },
		        new HTMLNode[] { HTMLNode.STRONG });
		HTMLNode inner = input.addChild("p").addChild("i");
		NodeL10n.getBase().addL10nSubstitution(inner, "SecurityLevels.networkThreatLevel.desc."+level,
		        new String[] { "bold" },
		        new HTMLNode[] { HTMLNode.STRONG });
	}

	public String postStep(HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String networkThreatLevel = request.getPartAsStringFailsafe("security-levels.networkThreatLevel", 128);
		SecurityLevels.NETWORK_THREAT_LEVEL newThreatLevel = SecurityLevels.parseNetworkThreatLevel(networkThreatLevel);

		/*If the user didn't select a network security level before clicking continue or the selected
		* security level could not be determined, redirect to the same page.*/
		if(newThreatLevel == null || !request.isPartSet("security-levels.networkThreatLevel")) {
			StringBuilder redirectTo = new StringBuilder(path()+"&opennet=");
			//Max length of 5 because 5 letters in false, 4 in true.
			redirectTo.append(request.getPartAsStringFailsafe("opennet", 5));
			return redirectTo.toString();
		}
		if((newThreatLevel == SecurityLevels.NETWORK_THREAT_LEVEL.MAXIMUM || newThreatLevel == SecurityLevels.NETWORK_THREAT_LEVEL.HIGH)) {
			//Make the user aware of the effects of high or maximum network threat if selected.
			//They must check
			if((!request.isPartSet("security-levels.networkThreatLevel.confirm")) &&
				(!request.isPartSet("security-levels.networkThreatLevel.tryConfirm"))) {
				PageNode page = ctx.getPageMaker().getPageNode(WizardL10n.l10n("networkSecurityPageTitle"), false, false, ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode content = page.content;

				HTMLNode infobox = content.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", WizardL10n.l10n("networkThreatLevelConfirmTitle." + newThreatLevel));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode formNode = ctx.addFormChild(infoboxContent, ".", "configFormSecLevels");
				formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.networkThreatLevel", networkThreatLevel });
				if(newThreatLevel == SecurityLevels.NETWORK_THREAT_LEVEL.MAXIMUM) {
					HTMLNode p = formNode.addChild("p");
					NodeL10n.getBase().addL10nSubstitution(p, "SecurityLevels.maximumNetworkThreatLevelWarning", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
					p.addChild("#", " ");
					NodeL10n.getBase().addL10nSubstitution(p, "SecurityLevels.maxSecurityYouNeedFriends", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
					formNode.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.networkThreatLevel.confirm", "off" }, WizardL10n.l10nSec("maximumNetworkThreatLevelCheckbox"));
				} else /*if(newThreatLevel == NETWORK_THREAT_LEVEL.HIGH)*/ {
					HTMLNode p = formNode.addChild("p");
					NodeL10n.getBase().addL10nSubstitution(p, "FirstTimeWizardToadlet.highNetworkThreatLevelWarning", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
					formNode.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.networkThreatLevel.confirm", "off" }, WizardL10n.l10n("highNetworkThreatLevelCheckbox"));
				}
				//Marker for step on POST side
				formNode.addChild("input",
				        new String [] { "type", "name", "value" },
				        new String [] { "hidden", "step", "SECURITY_NETWORK" });
				formNode.addChild("input",
				        new String[] { "type", "name", "value" },
				        new String[] { "hidden", "security-levels.networkThreatLevel.tryConfirm", "on" });
				formNode.addChild("input",
				        new String[] { "type", "name", "value" },
				        new String[] { "hidden", "seclevels", "on" });
				formNode.addChild("input",
				        new String[] { "type", "name", "value" },
				        new String[] { "submit", "networkSecurityF", WizardL10n.l10n("continue")});
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return null;
			} else if((!request.isPartSet("security-levels.networkThreatLevel.confirm")) &&
				        request.isPartSet("security-levels.networkThreatLevel.tryConfirm")) {
				//If the user did not check the box, redisplay the page.
				return path();
			}
		}
		//The user selected low or normal security, or confirmed high or maximum. Set the configuration
		//and continue to the physical security step.
		core.node.securityLevels.setThreatLevel(newThreatLevel);
		core.storeConfig();
		return FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.SECURITY_PHYSICAL;
	}
}
