package freenet.clients.http.wizardsteps;

import freenet.clients.http.ExternalLinkToadlet;
import freenet.clients.http.ToadletContext;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

import java.io.File;

/**
 * Allows the user to set the physical security level.
 */
public class GetSECURITY_PHYSICAL implements GetStep {

	private final NodeClientCore core;

	/**
	 * Constructs a new SECURITY_PHYSICAL GET handler.
	 * @param core used to check the current security level.
	 */
	public GetSECURITY_PHYSICAL(NodeClientCore core) {
		this.core = core;
	}

	@Override
	public String getTitleKey() {
		return "physicalSecurityPageTitle";
	}

	@Override
	public void getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

		infoboxHeader.addChild("#", WizardL10n.l10nSec("physicalThreatLevelShort"));
		infoboxContent.addChild("p", WizardL10n.l10nSec("physicalThreatLevel"));
		HTMLNode form = ctx.addFormChild(infoboxContent, ".", "physicalSecurityForm");
		HTMLNode div = form.addChild("div", "class", "opennetDiv");
		String controlName = "security-levels.physicalThreatLevel";
		HTMLNode swapWarning = div.addChild("p").addChild("i");
		NodeL10n.getBase().addL10nSubstitution(swapWarning, "SecurityLevels.physicalThreatLevelSwapfile",
			new String[]{"bold", "truecrypt"},
			new HTMLNode[]{HTMLNode.STRONG,
				HTMLNode.linkInNewWindow(ExternalLinkToadlet.escape("http://www.truecrypt.org/"))});
		if(File.separatorChar == '\\') {
			swapWarning.addChild("#", " " + WizardL10n.l10nSec("physicalThreatLevelSwapfileWindows"));
		}
		for(SecurityLevels.PHYSICAL_THREAT_LEVEL level : SecurityLevels.PHYSICAL_THREAT_LEVEL.values()) {
			HTMLNode input;
			input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
			input.addChild("b", WizardL10n.l10nSec("physicalThreatLevel.name." + level));
			input.addChild("#", ": ");
			NodeL10n.getBase().addL10nSubstitution(input, "SecurityLevels.physicalThreatLevel.choice."+level, new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
			if(level == SecurityLevels.PHYSICAL_THREAT_LEVEL.HIGH) {
				if(core.node.securityLevels.getPhysicalThreatLevel() != level) {
					// Add password form
					HTMLNode p = div.addChild("p");
					p.addChild("label", "for", "passwordBox", WizardL10n.l10nSec("setPasswordLabel")+":");
					p.addChild("input", new String[] { "id", "type", "name" }, new String[] { "passwordBox", "password", "masterPassword" });
				}
			}
		}
		div.addChild("#", WizardL10n.l10nSec("physicalThreatLevelEnd"));
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "physicalSecurityF", WizardL10n.l10n("continue")});
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
	}
}
