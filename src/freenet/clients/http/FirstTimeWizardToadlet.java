/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.client.filter.GenericReadFilterCallback;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.config.Option;
import freenet.l10n.NodeL10n;
import freenet.node.MasterKeysFileSizeException;
import freenet.node.MasterKeysWrongPasswordException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.node.Node.AlreadySetPasswordException;
import freenet.node.SecurityLevels.FRIENDS_THREAT_LEVEL;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/**
 * A first time wizard aimed to ease the configuration of the node.
 *
 * TODO: a choose your CSS step?
 */
public class FirstTimeWizardToadlet extends Toadlet {
	private final NodeClientCore core;
	private final Config config;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private enum WIZARD_STEP {
		WELCOME,
		// Before security levels, because once the network security level has been set, we won't redirect
		// the user to the wizard page.
		BROWSER_WARNING,
		// We have to set up UPnP before reaching the bandwidth stage, so we can autodetect bandwidth settings.
		MISC,
		OPENNET,
		SECURITY_NETWORK,
		SECURITY_PHYSICAL,
		NAME_SELECTION,
		BANDWIDTH,
		DATASTORE_SIZE,
		CONGRATZ,
		FINAL;
	}

	FirstTimeWizardToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.core = core;
		this.config = node.config;
	}

	public static final String TOADLET_URL = "/wizard/";

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}

		WIZARD_STEP currentStep = WIZARD_STEP.valueOf(request.getParam("step", WIZARD_STEP.WELCOME.toString()));

		if(currentStep == WIZARD_STEP.BROWSER_WARNING) {
			boolean incognito = request.isParameterSet("incognito");
			// Bug 3376: Opening Chrome in incognito mode from command line will open a new non-incognito window if the browser is already open.
			// See http://code.google.com/p/chromium/issues/detail?id=9636
			// When this is fixed, we should check the browser version and allow it for known good versions of Chrome.
			incognito = false;

			PageNode page = ctx.getPageMaker().getPageNode(incognito ? l10n("browserWarningIncognitoPageTitle") : l10n("browserWarningPageTitle"), false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

			if(incognito)
				infoboxHeader.addChild("#", l10n("browserWarningIncognitoShort"));
			else
				infoboxHeader.addChild("#", l10n("browserWarningShort"));
			NodeL10n.getBase().addL10nSubstitution(infoboxContent, incognito ? "FirstTimeWizardToadlet.browserWarningIncognito" : "FirstTimeWizardToadlet.browserWarning", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });

			if(incognito)
				infoboxContent.addChild("p", l10n("browserWarningIncognitoSuggestion"));
			else
				infoboxContent.addChild("p", l10n("browserWarningSuggestion"));

			infoboxContent.addChild("p").addChild("a", "href", "?step="+WIZARD_STEP.MISC, NodeL10n.getBase().getString("FirstTimeWizardToadlet.clickContinue"));

			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.OPENNET){
			PageNode page = ctx.getPageMaker().getPageNode(l10n("opennetChoicePageTitle"), false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

			infoboxHeader.addChild("#", l10n("opennetChoiceTitle"));
			
			infoboxContent.addChild("p", l10n("opennetChoiceIntroduction"));
			
			HTMLNode form = infoboxContent.addChild("form", new String[] { "action", "method", "id" }, new String[] { "GET", ".", "opennetChoiceForm" });
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "step", WIZARD_STEP.SECURITY_NETWORK.name() });
			
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
			
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "opennetF", NodeL10n.getBase().getString("FirstTimeWizardToadlet.continue")});
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
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.SECURITY_NETWORK) {
			PageNode page = ctx.getPageMaker().getPageNode(l10n("networkSecurityPageTitle"), false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			
			if(!request.isParameterSet("opennet")) {
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.OPENNET);
				return;
			}
			
			boolean opennet = Fields.stringToBool(request.getParam("opennet", "false"));

			infoboxHeader.addChild("#", l10n(opennet ? "networkThreatLevelHeaderOpennet" : "networkThreatLevelHeaderDarknet"));
			infoboxContent.addChild("p", l10n(opennet ? "networkThreatLevelIntroOpennet" : "networkThreatLevelIntroDarknet"));
			HTMLNode form = ctx.addFormChild(infoboxContent, ".", "networkSecurityForm");
			String controlName = "security-levels.networkThreatLevel";
			if(opennet) {
				HTMLNode div = form.addChild("div", "class", "opennetDiv");
				for(NETWORK_THREAT_LEVEL level : NETWORK_THREAT_LEVEL.OPENNET_VALUES) {
					HTMLNode input;
					input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
					input.addChild("b", l10nSec("networkThreatLevel.name."+level));
					input.addChild("#", ": ");
					NodeL10n.getBase().addL10nSubstitution(input, "SecurityLevels.networkThreatLevel.choice."+level, new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
					HTMLNode inner = input.addChild("p").addChild("i");
					NodeL10n.getBase().addL10nSubstitution(inner, "SecurityLevels.networkThreatLevel.desc."+level, new String[] { "bold" },
							new HTMLNode[] { HTMLNode.STRONG });
				}
			}
			if(!opennet) {
				HTMLNode div = form.addChild("div", "class", "darknetDiv");
				for(NETWORK_THREAT_LEVEL level : NETWORK_THREAT_LEVEL.DARKNET_VALUES) {
					HTMLNode input;
					input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
					input.addChild("b", l10nSec("networkThreatLevel.name."+level));
					input.addChild("#", ": ");
					NodeL10n.getBase().addL10nSubstitution(input, "SecurityLevels.networkThreatLevel.choice."+level, new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
					HTMLNode inner = input.addChild("p").addChild("i");
					NodeL10n.getBase().addL10nSubstitution(inner, "SecurityLevels.networkThreatLevel.desc."+level, new String[] { "bold" },
							new HTMLNode[] { HTMLNode.STRONG });
				}
				form.addChild("p").addChild("b", l10nSec("networkThreatLevel.opennetFriendsWarning"));
			}
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "networkSecurityF", NodeL10n.getBase().getString("FirstTimeWizardToadlet.continue")});
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.SECURITY_PHYSICAL) {
			PageNode page = ctx.getPageMaker().getPageNode(l10n("physicalSecurityPageTitle"), false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

			infoboxHeader.addChild("#", l10nSec("physicalThreatLevelShort"));
			infoboxContent.addChild("p", l10nSec("physicalThreatLevel"));
			HTMLNode form = ctx.addFormChild(infoboxContent, ".", "physicalSecurityForm");
			HTMLNode div = form.addChild("div", "class", "opennetDiv");
			String controlName = "security-levels.physicalThreatLevel";
			HTMLNode swapWarning = div.addChild("p").addChild("i");
			NodeL10n.getBase().addL10nSubstitution(swapWarning, "SecurityLevels.physicalThreatLevelSwapfile", new String[] { "bold", "truecrypt" }, new HTMLNode[] { HTMLNode.STRONG, HTMLNode.linkInNewWindow(GenericReadFilterCallback.escapeURL("http://www.truecrypt.org/")) });
			if(File.separatorChar == '\\')
				swapWarning.addChild("#", " " + l10nSec("physicalThreatLevelSwapfileWindows"));
			for(PHYSICAL_THREAT_LEVEL level : PHYSICAL_THREAT_LEVEL.values()) {
				HTMLNode input;
				input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
				input.addChild("b", l10nSec("physicalThreatLevel.name."+level));
				input.addChild("#", ": ");
				NodeL10n.getBase().addL10nSubstitution(input, "SecurityLevels.physicalThreatLevel.choice."+level, new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
				if(level == PHYSICAL_THREAT_LEVEL.HIGH) {
					if(core.node.securityLevels.getPhysicalThreatLevel() != level) {
						// Add password form
						HTMLNode p = div.addChild("p");
						p.addChild("label", "for", "passwordBox", l10nSec("setPasswordLabel")+":");
						p.addChild("input", new String[] { "id", "type", "name" }, new String[] { "passwordBox", "password", "masterPassword" });
					}
				}
			}
			div.addChild("#", l10nSec("physicalThreatLevelEnd"));
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "physicalSecurityF", NodeL10n.getBase().getString("FirstTimeWizardToadlet.continue")});
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.NAME_SELECTION) {
			// Attempt to skip one step if possible: opennet nodes don't need a name
			if(Boolean.valueOf(request.getParam("opennet"))) {
				super.writeTemporaryRedirect(ctx, "step3", TOADLET_URL+"?step="+WIZARD_STEP.BANDWIDTH);
				return;
			}
			PageNode page = ctx.getPageMaker().getPageNode(l10n("step2Title"), false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode nnameInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode nnameInfoboxHeader = nnameInfobox.addChild("div", "class", "infobox-header");
			HTMLNode nnameInfoboxContent = nnameInfobox.addChild("div", "class", "infobox-content");

			nnameInfoboxHeader.addChild("#", l10n("chooseNodeName"));
			nnameInfoboxContent.addChild("#", l10n("chooseNodeNameLong"));
			HTMLNode nnameForm = ctx.addFormChild(nnameInfoboxContent, ".", "nnameForm");
			nnameForm.addChild("input", "name", "nname");

			nnameForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "nnameF", NodeL10n.getBase().getString("FirstTimeWizardToadlet.continue")});
			nnameForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.BANDWIDTH) {
			// Attempt to skip one step if possible
			int autodetectedLimit = canAutoconfigureBandwidth();
			PageNode page = ctx.getPageMaker().getPageNode(l10n("step3Title"), false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");

			bandwidthnfoboxHeader.addChild("#", l10n("bandwidthLimit"));
			bandwidthInfoboxContent.addChild("#", l10n("bandwidthLimitLong"));
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "bwForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "bw");

			@SuppressWarnings("unchecked")
			Option<Integer> sizeOption = (Option<Integer>) config.get("node").getOption("outputBandwidthLimit");
			if(!sizeOption.isDefault()) {
				int current = sizeOption.getValue();
				result.addChild("option", new String[] { "value", "selected" }, new String[] { SizeUtil.formatSize(current), "on" }, l10n("currentSpeed")+" "+SizeUtil.formatSize(current)+"/s");
			} else if(autodetectedLimit != -1)
				result.addChild("option", new String[] { "value", "selected" }, new String[] { SizeUtil.formatSize(autodetectedLimit), "on" }, l10n("autodetectedSuggestedLimit")+" "+SizeUtil.formatSize(autodetectedLimit)+"/s");

			// don't forget to update handlePost too if you change that!
			if(autodetectedLimit != 8192)
				result.addChild("option", "value", "8K", l10n("bwlimitLowerSpeed"));
			// Special case for 128kbps to increase performance at the cost of some link degradation. Above that we use 50% of the limit.
			result.addChild("option", "value", "12K", "512+/128 kbps (12KB/s)");
			if(autodetectedLimit != -1 || !sizeOption.isDefault())
				result.addChild("option", "value", "16K", "1024+/256 kbps (16KB/s)");
			else
				result.addChild("option", new String[] { "value", "selected" }, new String[] { "16K", "selected" }, "1024+/256 kbps (16KB/s)");
			result.addChild("option", "value", "32K", "1024+/512 kbps (32K/s)");
			result.addChild("option", "value", "64K", "1024+/1024 kbps (64K/s)");
			result.addChild("option", "value", "1000K", l10n("bwlimitHigherSpeed"));

			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "bwF", NodeL10n.getBase().getString("FirstTimeWizardToadlet.continue")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
			bandwidthInfoboxContent.addChild("#", l10n("bandwidthLimitAfter"));
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.DATASTORE_SIZE) {
			// Attempt to skip one step if possible
			PageNode page = ctx.getPageMaker().getPageNode(l10n("step4Title"), false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");

			bandwidthnfoboxHeader.addChild("#", l10n("datastoreSize"));
			bandwidthInfoboxContent.addChild("#", l10n("datastoreSizeLong"));
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "dsForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "ds");

			long autodetectedSize = canAutoconfigureDatastoreSize();

			@SuppressWarnings("unchecked")
			Option<Long> sizeOption = (Option<Long>) config.get("node").getOption("storeSize");
			if(!sizeOption.isDefault()) {
				long current = sizeOption.getValue();
				result.addChild("option", new String[] { "value", "selected" }, new String[] { SizeUtil.formatSize(current), "on" }, l10n("currentPrefix")+" "+SizeUtil.formatSize(current));
			} else if(autodetectedSize != -1)
				result.addChild("option", new String[] { "value", "selected" }, new String[] { SizeUtil.formatSize(autodetectedSize), "on" }, SizeUtil.formatSize(autodetectedSize));
			if(autodetectedSize != 512*1024*1024)
				result.addChild("option", "value", "512M", "512 MiB");
			result.addChild("option", "value", "1G", "1 GiB");
			if(autodetectedSize != -1 || !sizeOption.isDefault())
				result.addChild("option", "value", "2G", "2 GiB");
			else
				result.addChild("option", new String[] { "value", "selected" }, new String[] { "2G", "on" }, "2GiB");
			result.addChild("option", "value", "3G", "3 GiB");
			result.addChild("option", "value", "5G", "5 GiB");
			result.addChild("option", "value", "10G", "10 GiB");
			result.addChild("option", "value", "20G", "20 GiB");
			result.addChild("option", "value", "30G", "30 GiB");
			result.addChild("option", "value", "50G", "50 GiB");
			result.addChild("option", "value", "100G", "100 GiB");

			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "dsF", NodeL10n.getBase().getString("FirstTimeWizardToadlet.continue")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.MISC) {
			PageNode page = ctx.getPageMaker().getPageNode(l10n("stepMiscTitle"), false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

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
			miscInfoboxContent.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" },
					new String[] { "checkbox", "on", "jstun", "true" }, l10n("enableJSTUN"));

			miscInfoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "miscF", NodeL10n.getBase().getString("FirstTimeWizardToadlet.continue")});
			miscInfoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		}else if(currentStep == WIZARD_STEP.CONGRATZ) {
			PageNode page = ctx.getPageMaker().getPageNode(l10n("step7Title"), true, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode congratzInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode congratzInfoboxHeader = congratzInfobox.addChild("div", "class", "infobox-header");
			HTMLNode congratzInfoboxContent = congratzInfobox.addChild("div", "class", "infobox-content");

			congratzInfoboxHeader.addChild("#", l10n("congratz"));
			congratzInfoboxContent.addChild("p", l10n("congratzLong"));

			congratzInfoboxContent.addChild("a", "href", "?step="+WIZARD_STEP.FINAL, NodeL10n.getBase().getString("FirstTimeWizardToadlet.continueEnd"));

			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.FINAL) {
			try {
				config.get("fproxy").set("hasCompletedWizard", true);
                                config.store();
				this.writeTemporaryRedirect(ctx, "Return to home", "/");
			} catch (ConfigException e) {
				Logger.error(this, e.getMessage(), e);
			}
			return;
		}

		PageNode page = ctx.getPageMaker().getPageNode(l10n("homepageTitle"), false, ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		HTMLNode welcomeInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode welcomeInfoboxHeader = welcomeInfobox.addChild("div", "class", "infobox-header");
		HTMLNode welcomeInfoboxContent = welcomeInfobox.addChild("div", "class", "infobox-content");
		welcomeInfoboxHeader.addChild("#", l10n("welcomeInfoboxTitle"));

		HTMLNode firstParagraph = welcomeInfoboxContent.addChild("p");
		firstParagraph.addChild("#", l10n("welcomeInfoboxContent1"));
		HTMLNode secondParagraph = welcomeInfoboxContent.addChild("p");
		boolean incognito = request.isParameterSet("incognito");
		String append = incognito ? "&incognito=true" : "";
		secondParagraph.addChild("a", "href", "?step="+WIZARD_STEP.BROWSER_WARNING+append).addChild("#", NodeL10n.getBase().getString("FirstTimeWizardToadlet.clickContinue"));

		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private String l10nSec(String key) {
		return NodeL10n.getBase().getString("SecurityLevels."+key);
	}

	private String l10nSec(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("SecurityLevels."+key, pattern, value);
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}

		String passwd = request.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(logMINOR) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
			super.writeTemporaryRedirect(ctx, "invalid/unhandled data", "/");
			return;
		}

		if(request.isPartSet("security-levels.networkThreatLevel")) {
			// We don't require a confirmation here, since it's one page at a time, so there's less information to
			// confuse the user, and we don't know whether the node has friends yet etc.
			// FIXME should we have confirmation here???
			String networkThreatLevel = request.getPartAsString("security-levels.networkThreatLevel", 128);
			NETWORK_THREAT_LEVEL newThreatLevel = SecurityLevels.parseNetworkThreatLevel(networkThreatLevel);
			if(newThreatLevel == null) {
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_NETWORK);
				return;
			}
			if((newThreatLevel == NETWORK_THREAT_LEVEL.MAXIMUM || newThreatLevel == NETWORK_THREAT_LEVEL.HIGH)) {
				if((!request.isPartSet("security-levels.networkThreatLevel.confirm")) &&
					(!request.isPartSet("security-levels.networkThreatLevel.tryConfirm"))) {
					PageNode page = ctx.getPageMaker().getPageNode(l10n("networkSecurityPageTitle"), false, ctx);
					HTMLNode pageNode = page.outer;
					HTMLNode content = page.content;

					HTMLNode infobox = content.addChild("div", "class", "infobox infobox-information");
					infobox.addChild("div", "class", "infobox-header", l10n("networkThreatLevelConfirmTitle."+newThreatLevel));
					HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
					HTMLNode formNode = ctx.addFormChild(infoboxContent, ".", "configFormSecLevels");
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.networkThreatLevel", networkThreatLevel });
					if(newThreatLevel == NETWORK_THREAT_LEVEL.MAXIMUM) {
						HTMLNode p = formNode.addChild("p");
						NodeL10n.getBase().addL10nSubstitution(p, "SecurityLevels.maximumNetworkThreatLevelWarning", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
						p.addChild("#", " ");
						NodeL10n.getBase().addL10nSubstitution(p, "SecurityLevels.maxSecurityYouNeedFriends", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
						formNode.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.networkThreatLevel.confirm", "off" }, l10nSec("maximumNetworkThreatLevelCheckbox"));
					} else /*if(newThreatLevel == NETWORK_THREAT_LEVEL.HIGH)*/ {
						HTMLNode p = formNode.addChild("p");
						NodeL10n.getBase().addL10nSubstitution(p, "FirstTimeWizardToadlet.highNetworkThreatLevelWarning", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
						formNode.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.networkThreatLevel.confirm", "off" }, l10n("highNetworkThreatLevelCheckbox"));
					}
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.networkThreatLevel.tryConfirm", "on" });
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
					formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("continue")});
					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return;
				} else if((!request.isPartSet("security-levels.networkThreatLevel.confirm")) &&
						request.isPartSet("security-levels.networkThreatLevel.tryConfirm")) {
					super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_NETWORK);
					return;
				}
			}
			core.node.securityLevels.setThreatLevel(newThreatLevel);
			core.storeConfig();
			super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_PHYSICAL);
			return;
		} else if(request.isPartSet("security-levels.physicalThreatLevel")) {
			// We don't require a confirmation here, since it's one page at a time, so there's less information to
			// confuse the user, and we don't know whether the node has friends yet etc.
			// FIXME should we have confirmation here???
			String physicalThreatLevel = request.getPartAsString("security-levels.physicalThreatLevel", 128);
			PHYSICAL_THREAT_LEVEL oldThreatLevel = core.node.securityLevels.getPhysicalThreatLevel();
			PHYSICAL_THREAT_LEVEL newThreatLevel = SecurityLevels.parsePhysicalThreatLevel(physicalThreatLevel);
			if(logMINOR) Logger.minor(this, "Old threat level: "+oldThreatLevel+" new threat level: "+newThreatLevel);
			if(newThreatLevel == null) {
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_PHYSICAL);
				return;
			}
			if(newThreatLevel == PHYSICAL_THREAT_LEVEL.HIGH && oldThreatLevel != newThreatLevel) {
				// Check for password
				String pass = request.getPartAsString("masterPassword", SecurityLevelsToadlet.MAX_PASSWORD_LENGTH);
				if(pass != null && pass.length() > 0) {
					try {
						if(oldThreatLevel == PHYSICAL_THREAT_LEVEL.NORMAL || oldThreatLevel == PHYSICAL_THREAT_LEVEL.LOW)
							core.node.changeMasterPassword("", pass, true);
						else
							core.node.setMasterPassword(pass, true);
					} catch (AlreadySetPasswordException e) {
						// Do nothing, already set a password.
					} catch (MasterKeysWrongPasswordException e) {
						System.err.println("Wrong password!");
						PageNode page = ctx.getPageMaker().getPageNode(l10n("passwordPageTitle"), false, ctx);
						HTMLNode pageNode = page.outer;
						HTMLNode contentNode = page.content;

						HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
								l10n("passwordWrongTitle"), contentNode, null, true).
								addChild("div", "class", "infobox-content");

						SecurityLevelsToadlet.generatePasswordFormPage(true, ctx.getContainer(), content, true, false, true, newThreatLevel.name(), null);

						addBackToPhysicalSeclevelsLink(content);

						writeHTMLReply(ctx, 200, "OK", pageNode.generate());
						return;
					} catch (MasterKeysFileSizeException e) {
						sendPasswordFileCorruptedPage(e.isTooBig(), ctx, false, true);
						return;
					}
				} else {
					// Must set a password!
					PageNode page = ctx.getPageMaker().getPageNode(l10n("passwordPageTitle"), false, ctx);
					HTMLNode pageNode = page.outer;
					HTMLNode contentNode = page.content;

					HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
							l10nSec("enterPasswordTitle"), contentNode, null, true).
							addChild("div", "class", "infobox-content");

					if(pass != null && pass.length() == 0) {
						content.addChild("p", l10nSec("passwordNotZeroLength"));
					}

					SecurityLevelsToadlet.generatePasswordFormPage(false, ctx.getContainer(), content, true, false, true, newThreatLevel.name(), null);

					addBackToPhysicalSeclevelsLink(content);

					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return;
				}
			}
			if((newThreatLevel == PHYSICAL_THREAT_LEVEL.LOW || newThreatLevel == PHYSICAL_THREAT_LEVEL.NORMAL) &&
					oldThreatLevel == PHYSICAL_THREAT_LEVEL.HIGH) {
				// Check for password
				String pass = request.getPartAsString("masterPassword", SecurityLevelsToadlet.MAX_PASSWORD_LENGTH);
				if(pass != null && pass.length() > 0) {
					// This is actually the OLD password ...
					try {
						core.node.changeMasterPassword(pass, "", true);
					} catch (IOException e) {
						if(!core.node.getMasterPasswordFile().exists()) {
							// Ok.
							System.out.println("Master password file no longer exists, assuming this is deliberate");
						} else {
							System.err.println("Cannot change password as cannot write new passwords file: "+e);
							e.printStackTrace();
							String msg = "<html><head><title>"+l10n("cantWriteNewMasterKeysFileTitle")+
									"</title></head><body><h1>"+l10n("cantWriteNewMasterKeysFileTitle")+"</h1><pre>";
							StringWriter sw = new StringWriter();
							PrintWriter pw = new PrintWriter(sw);
							e.printStackTrace(pw);
							pw.flush();
							msg = msg + sw.toString() + "</pre></body></html>";
							writeHTMLReply(ctx, 500, "Internal Error", msg);
							return;
						}
					} catch (MasterKeysWrongPasswordException e) {
						System.err.println("Wrong password!");
						PageNode page = ctx.getPageMaker().getPageNode(l10n("passwordForDecryptTitle"), false, ctx);
						HTMLNode pageNode = page.outer;
						HTMLNode contentNode = page.content;

						HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
								l10n("passwordWrongTitle"), contentNode, null, true).
								addChild("div", "class", "infobox-content");

						SecurityLevelsToadlet.generatePasswordFormPage(true, ctx.getContainer(), content, true, false, false, newThreatLevel.name(), null);

						addBackToPhysicalSeclevelsLink(content);

						writeHTMLReply(ctx, 200, "OK", pageNode.generate());
						return;
					} catch (MasterKeysFileSizeException e) {
						sendPasswordFileCorruptedPage(e.isTooBig(), ctx, false, true);
						return;
					} catch (AlreadySetPasswordException e) {
						System.err.println("Already set a password when changing it - maybe master.keys copied in at the wrong moment???");
					}
				} else if(core.node.getMasterPasswordFile().exists()) {
					// We need the old password
					PageNode page = ctx.getPageMaker().getPageNode(l10n("passwordForDecryptTitle"), false, ctx);
					HTMLNode pageNode = page.outer;
					HTMLNode contentNode = page.content;

					HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
							l10nSec("passwordForDecryptTitle"), contentNode, null, true).
							addChild("div", "class", "infobox-content");

					if(pass != null && pass.length() == 0) {
						content.addChild("p", l10nSec("passwordNotZeroLength"));
					}

					SecurityLevelsToadlet.generatePasswordFormPage(false, ctx.getContainer(), content, true, true, false, newThreatLevel.name(), null);

					addBackToPhysicalSeclevelsLink(content);

					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return;

				}

			}
			if(newThreatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
				try {
					core.node.killMasterKeysFile();
				} catch (IOException e) {
					sendCantDeleteMasterKeysFile(ctx, newThreatLevel.name());
					return;
				}
			}
			core.node.securityLevels.setThreatLevel(newThreatLevel);
			core.storeConfig();
			try {
				core.node.lateSetupDatabase(null);
			} catch (MasterKeysWrongPasswordException e) {
				// Ignore, impossible???
				System.err.println("Failed starting up database while switching physical security level to "+newThreatLevel+" from "+oldThreatLevel+" : wrong password - this is impossible, it should have been handled by the other cases, suggest you remove master.keys");
			} catch (MasterKeysFileSizeException e) {
				System.err.println("Failed starting up database while switching physical security level to "+newThreatLevel+" from "+oldThreatLevel+" : "+core.node.getMasterPasswordFile()+" is too " + e.sizeToString());
			}
			super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.NAME_SELECTION+"&opennet="+core.node.isOpennetEnabled());
			return;
		} else if(request.isPartSet("nnameF")) {
			String selectedNName = request.getPartAsString("nname", 128);
			try {
				config.get("node").set("name", selectedNName);
				Logger.normal(this, "The node name has been set to "+ selectedNName);
			} catch (ConfigException e) {
				Logger.error(this, "Should not happen, please report!" + e, e);
			}
			super.writeTemporaryRedirect(ctx, "step3", TOADLET_URL+"?step="+WIZARD_STEP.BANDWIDTH);
			return;
		} else if(request.isPartSet("bwF")) {
			_setUpstreamBandwidthLimit(request.getPartAsString("bw", 20)); // drop down options may be 6 chars or less, but formatted ones e.g. old value if re-running can be more
			super.writeTemporaryRedirect(ctx, "step4", TOADLET_URL+"?step="+WIZARD_STEP.DATASTORE_SIZE);
			return;
		} else if(request.isPartSet("dsF")) {
			_setDatastoreSize(request.getPartAsString("ds", 20)); // drop down options may be 6 chars or less, but formatted ones e.g. old value if re-running can be more
			super.writeTemporaryRedirect(ctx, "step5", TOADLET_URL+"?step="+WIZARD_STEP.CONGRATZ);
			return;
		} else if(request.isPartSet("miscF")) {
			try {
				config.get("node.updater").set("autoupdate", Boolean.parseBoolean(request.getPartAsString("autodeploy", 10)));
			} catch (ConfigException e) {
				Logger.error(this, "Should not happen, please report!" + e, e);
			}
			final boolean enableUPnP = request.isPartSet("upnp");
			final boolean enableJSTUN = request.isPartSet("jstun");
			if(enableUPnP != core.node.pluginManager.isPluginLoaded("plugins.UPnP.UPnP")) {
					// We can probably get connected without it, so don't force HTTPS.
					// We'd have to ask the user anyway...
					core.node.executor.execute(new Runnable() {

						private final boolean enable = enableUPnP;

						public void run() {
							if(enable)
								core.node.pluginManager.startPluginOfficial("UPnP", true, false, false);
							else
								core.node.pluginManager.killPluginByClass("plugins.UPnP.UPnP", 5000);
						}

					});
			}
			if(enableJSTUN != core.node.pluginManager.isPluginLoaded("plugins.JSTUN.JSTUN")) {
					core.node.executor.execute(new Runnable() {

						private final boolean enable = enableJSTUN;

						public void run() {
							// We can probably get connected without it, so don't force HTTPS.
							// We'd have to ask the user anyway...
							if(enable)
							core.node.pluginManager.startPluginOfficial("JSTUN", true, false, false);
							else
								core.node.pluginManager.killPluginByClass("plugins.JSTUN.JSTUN", 5000);
						}
					});
			}
			super.writeTemporaryRedirect(ctx, "step7", TOADLET_URL+"?step="+WIZARD_STEP.OPENNET);
			return;

		}

		super.writeTemporaryRedirect(ctx, "invalid/unhandled data", TOADLET_URL);
	}

	private void sendPasswordFileCorruptedPage(boolean tooBig, ToadletContext ctx, boolean forSecLevels, boolean forFirstTimeWizard) throws ToadletContextClosedException, IOException {
		writeHTMLReply(ctx, 500, "OK", SecurityLevelsToadlet.sendPasswordFileCorruptedPageInner(tooBig, ctx, forSecLevels, forFirstTimeWizard, core.node.getMasterPasswordFile().getPath(), core.node).generate());
	}

	private void addBackToPhysicalSeclevelsLink(HTMLNode content) {
		content.addChild("a", "href", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_PHYSICAL, l10n("backToSecurityLevels"));
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key);
	}
	
	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key, pattern, value);
	}
	
	private void _setDatastoreSize(String selectedStoreSize) {
		try {
			long size = Fields.parseLong(selectedStoreSize);
			// client cache: 10% up to 200MB
			long clientCacheSize = Math.min(size / 10, 200*1024*1024);
			// recent requests cache / slashdot cache / ULPR cache
			int upstreamLimit = config.get("node").getInt("outputBandwidthLimit");
			int downstreamLimit = config.get("node").getInt("inputBandwidthLimit");
			// is used for remote stuff, so go by the minimum of the two
			int limit;
			if(downstreamLimit <= 0) limit = upstreamLimit;
			else limit = Math.min(downstreamLimit, upstreamLimit);
			// 35KB/sec limit has been seen to have 0.5 store writes per second.
			// So saying we want to have space to cache everything is only doubling that ...
			// OTOH most stuff is at low enough HTL to go to the datastore and thus not to
			// the slashdot cache, so we could probably cut this significantly...
			long lifetime = config.get("node").getLong("slashdotCacheLifetime");
			long maxSlashdotCacheSize = (lifetime / 1000) * limit;
			long slashdotCacheSize = Math.min(size / 10, maxSlashdotCacheSize);

			long storeSize = size - (clientCacheSize + slashdotCacheSize);

			System.out.println("Setting datastore size to "+Fields.longToString(storeSize, true));
			config.get("node").set("storeSize", Fields.longToString(storeSize, true));
			if(config.get("node").getString("storeType").equals("ram"))
				config.get("node").set("storeType", "salt-hash");
			System.out.println("Setting client cache size to "+Fields.longToString(clientCacheSize, true));
			config.get("node").set("clientCacheSize", Fields.longToString(clientCacheSize, true));
			if(config.get("node").getString("clientCacheType").equals("ram"))
				config.get("node").set("clientCacheType", "salt-hash");
			System.out.println("Setting slashdot/ULPR/recent requests cache size to "+Fields.longToString(slashdotCacheSize, true));
			config.get("node").set("slashdotCacheSize", Fields.longToString(slashdotCacheSize, true));


			Logger.normal(this, "The storeSize has been set to " + selectedStoreSize);
		} catch(ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	private void _setUpstreamBandwidthLimit(String selectedUploadSpeed) {
		try {
			config.get("node").set("outputBandwidthLimit", selectedUploadSpeed);
			Logger.normal(this, "The outputBandwidthLimit has been set to " + selectedUploadSpeed);
		} catch (ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	private void _setDownstreamBandwidthLimit(String selectedDownloadSpeed) {
		try {
			config.get("node").set("inputBandwidthLimit", selectedDownloadSpeed);
			Logger.normal(this, "The inputBandwidthLimit has been set to " + selectedDownloadSpeed);
		} catch(ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	private int canAutoconfigureBandwidth() {
		if(!config.get("node").getOption("outputBandwidthLimit").isDefault())
			return -1;
		FredPluginBandwidthIndicator bwIndicator = core.node.ipDetector.getBandwidthIndicator();
		if(bwIndicator == null)
			return -1;

		int downstreamBWLimit = bwIndicator.getDownstreamMaxBitRate();
		int upstreamBWLimit = bwIndicator.getUpstramMaxBitRate();
		if((downstreamBWLimit > 0 && downstreamBWLimit < 65536) || (upstreamBWLimit > 0 && upstreamBWLimit < 8192)) {
			// These are kilobits, not bits, per second, right?
			// Assume the router is buggy and don't autoconfigure.
			// Nothing that implements UPnP would be that slow.
			System.err.println("Buggy router? downstream: "+downstreamBWLimit+" upstream: "+upstreamBWLimit+" - these are supposed to be in bits per second!");
			return -1;
		}
		if(downstreamBWLimit > 0) {
			int bytes = (downstreamBWLimit / 8) - 1;
			String downstreamBWLimitString = SizeUtil.formatSize(bytes * 2 / 3);
			// Set the downstream limit anyway, it is usually so high as to be irrelevant.
			// The user can choose the upstream limit.
			_setDownstreamBandwidthLimit(downstreamBWLimitString);
			Logger.normal(this, "The node has a bandwidthIndicator: it has reported downstream=" + downstreamBWLimit + "bits/sec... we will use " + downstreamBWLimitString + " and skip the bandwidth selection step of the wizard.");
		}

		// We don't mind if the downstreamBWLimit couldn't be set, but upstreamBWLimit is important
		if(upstreamBWLimit > 0) {
			int bytes = (upstreamBWLimit / 8) - 1;
			if(bytes < 16384) return 8192;
			return bytes / 2;
		}else
			return -1;
	}

	private long canAutoconfigureDatastoreSize() {
		if(!config.get("node").getOption("storeSize").isDefault())
			return -1;

		long freeSpace = FileUtil.getFreeSpace(core.node.getStoreDir());

		if(freeSpace <= 0)
			return -1;
		else {
			long shortSize = -1;
			if(freeSpace / 20 > 1024 * 1024 * 1024) { // 20GB+ => 5%, limit 256GB
				// If 20GB+ free, 5% of available disk space.
				// Maximum of 256GB. That's a 128MB bloom filter.
				shortSize = Math.min(freeSpace / 20, 256*1024*1024*1024L);
			}else if(freeSpace / 10 > 1024 * 1024 * 1024) { // 10GB+ => 10%
				// If 10GB+ free, 10% of available disk space.
				shortSize = freeSpace / 10;
			}else if(freeSpace / 5 > 1024 * 1024 * 1024) { // 5GB+ => 512MB
				// If 5GB+ free, default to 512MB
				shortSize = 512*1024*1024;
			}else // <5GB => 256MB
				shortSize = 256*1024*1024;

			return shortSize;
		}
	}

	private void sendCantDeleteMasterKeysFile(ToadletContext ctx, String physicalSecurityLevel) throws ToadletContextClosedException, IOException {
		HTMLNode pageNode = SecurityLevelsToadlet.sendCantDeleteMasterKeysFileInner(ctx, core.node.getMasterPasswordFile().getPath(), false, physicalSecurityLevel, this.core.node);
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	@Override
	public String path() {
		return TOADLET_URL;
	}
}
