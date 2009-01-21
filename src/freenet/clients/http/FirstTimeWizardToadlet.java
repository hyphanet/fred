/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.config.Option;
import freenet.config.WrapperConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.node.SecurityLevels.FRIENDS_THREAT_LEVEL;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SizeUtil;
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
	
	private enum WIZARD_STEP {
		WELCOME,
		// Before security levels, because once the network security level has been set, we won't redirect
		// the user to the wizard page.
		BROWSER_WARNING,
		SECURITY_NETWORK,
		SECURITY_FRIENDS,
		SECURITY_PHYSICAL,
		NAME_SELECTION,
		BANDWIDTH,
		DATASTORE_SIZE,
		MEMORY,
		CONGRATZ,
		FINAL;
	}
	
	
	FirstTimeWizardToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.core = core;
		this.config = node.config;
	}
	
	public static final String TOADLET_URL = "/wizard/";
	
	@Override
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		WIZARD_STEP currentStep = WIZARD_STEP.valueOf(request.getParam("step", WIZARD_STEP.WELCOME.toString()));
		
		if(currentStep == WIZARD_STEP.BROWSER_WARNING) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("browserWarningPageTitle"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			
			infoboxHeader.addChild("#", l10n("browserWarningShort"));
			L10n.addL10nSubstitution(infoboxContent, "FirstTimeWizardToadlet.browserWarning", new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			infoboxContent.addChild("p", l10n("browserWarningSuggestion"));
			
			infoboxContent.addChild("p").addChild("a", "href", "?step="+WIZARD_STEP.SECURITY_NETWORK, L10n.getString("FirstTimeWizardToadlet.clickContinue"));

			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.SECURITY_NETWORK) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("networkSecurityPageTitle"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			
			infoboxHeader.addChild("#", l10nSec("networkThreatLevelShort"));
			infoboxContent.addChild("p", l10nSec("networkThreatLevel"));
			HTMLNode form = ctx.addFormChild(infoboxContent, ".", "networkSecurityForm");
			HTMLNode div = form.addChild("div", "class", "opennetDiv");
			String controlName = "security-levels.networkThreatLevel";
			for(NETWORK_THREAT_LEVEL level : NETWORK_THREAT_LEVEL.values()) {
				HTMLNode input;
				input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
				input.addChild("b", l10nSec("networkThreatLevel.name."+level));
				input.addChild("#", ": ");
				L10n.addL10nSubstitution(input, "SecurityLevels.networkThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
				HTMLNode inner = input.addChild("p").addChild("i");
				L10n.addL10nSubstitution(inner, "SecurityLevels.networkThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			}
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "networkSecurityF", L10n.getString("FirstTimeWizardToadlet.continue")});
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.SECURITY_FRIENDS) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("friendsSecurityPageTitle"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			
			infoboxHeader.addChild("#", l10nSec("friendsThreatLevelShort"));
			infoboxContent.addChild("p", l10nSec("friendsThreatLevel"));
			HTMLNode form = ctx.addFormChild(infoboxContent, ".", "friendsSecurityForm");
			HTMLNode div = form.addChild("div", "class", "opennetDiv");
			String controlName = "security-levels.friendsThreatLevel";
			for(FRIENDS_THREAT_LEVEL level : FRIENDS_THREAT_LEVEL.values()) {
				HTMLNode input;
				input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
				input.addChild("b", l10nSec("friendsThreatLevel.name."+level));
				input.addChild("#", ": ");
				L10n.addL10nSubstitution(input, "SecurityLevels.friendsThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
				HTMLNode inner = input.addChild("p").addChild("i");
				L10n.addL10nSubstitution(inner, "SecurityLevels.friendsThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			}
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "friendsSecurityF", L10n.getString("FirstTimeWizardToadlet.continue")});
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.SECURITY_PHYSICAL) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("physicalSecurityPageTitle"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			
			infoboxHeader.addChild("#", l10nSec("physicalThreatLevelShort"));
			infoboxContent.addChild("p", l10nSec("physicalThreatLevel"));
			HTMLNode form = ctx.addFormChild(infoboxContent, ".", "physicalSecurityForm");
			HTMLNode div = form.addChild("div", "class", "opennetDiv");
			String controlName = "security-levels.physicalThreatLevel";
			for(PHYSICAL_THREAT_LEVEL level : PHYSICAL_THREAT_LEVEL.values()) {
				HTMLNode input;
				input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
				input.addChild("b", l10nSec("physicalThreatLevel.name."+level));
				input.addChild("#", ": ");
				L10n.addL10nSubstitution(input, "SecurityLevels.physicalThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
				HTMLNode inner = input.addChild("p").addChild("i");
				L10n.addL10nSubstitution(inner, "SecurityLevels.physicalThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			}
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "physicalSecurityF", L10n.getString("FirstTimeWizardToadlet.continue")});
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.NAME_SELECTION) {
			// Attempt to skip one step if possible: opennet nodes don't need a name
			if(Boolean.valueOf(request.getParam("opennet"))) {
				super.writeTemporaryRedirect(ctx, "step3", TOADLET_URL+"?step="+WIZARD_STEP.BANDWIDTH);
				return;
			}
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step2Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode nnameInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode nnameInfoboxHeader = nnameInfobox.addChild("div", "class", "infobox-header");
			HTMLNode nnameInfoboxContent = nnameInfobox.addChild("div", "class", "infobox-content");
			
			nnameInfoboxHeader.addChild("#", l10n("chooseNodeName"));
			nnameInfoboxContent.addChild("#", l10n("chooseNodeNameLong"));
			HTMLNode nnameForm = ctx.addFormChild(nnameInfoboxContent, ".", "nnameForm");
			nnameForm.addChild("input", "name", "nname");
			
			nnameForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "nnameF", L10n.getString("FirstTimeWizardToadlet.continue")});
			nnameForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.BANDWIDTH) {
			// Attempt to skip one step if possible
			if(canAutoconfigureBandwidth()){
				super.writeTemporaryRedirect(ctx, "step4", TOADLET_URL+"?step="+WIZARD_STEP.DATASTORE_SIZE);
				return;
			}
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step3Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");
			
			bandwidthnfoboxHeader.addChild("#", l10n("bandwidthLimit"));
			bandwidthInfoboxContent.addChild("#", l10n("bandwidthLimitLong"));
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "bwForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "bw");
			
			Option sizeOption = config.get("node").getOption("outputBandwidthLimit");
			if(!sizeOption.isDefault()) {
				int current = (Integer)sizeOption.getValue();
				result.addChild("option", new String[] { "value", "selected" }, new String[] { SizeUtil.formatSize(current), "on" }, l10n("currentSpeed")+" "+SizeUtil.formatSize(current)+"/s");
			}

			// don't forget to update handlePost too if you change that!
			result.addChild("option", "value", "8K", l10n("bwlimitLowerSpeed"));
			// Special case for 128kbps to increase performance at the cost of some link degradation. Above that we use 50% of the limit.
			result.addChild("option", "value", "12K", "512+/128 kbps (12KB/s)");
			if(!sizeOption.isDefault())
				result.addChild("option", "value", "16K", "1024+/256 kbps (16KB/s)");
			else
				result.addChild("option", new String[] { "value", "selected" }, new String[] { "16K", "selected" }, "1024+/256 kbps (16KB/s)");
			result.addChild("option", "value", "32K", "1024+/512 kbps (32K/s)");
			result.addChild("option", "value", "64K", "1024+/1024 kbps (64K/s)");
			result.addChild("option", "value", "1000K", l10n("bwlimitHigherSpeed"));
			
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "bwF", L10n.getString("FirstTimeWizardToadlet.continue")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.DATASTORE_SIZE) {
			// Attempt to skip one step if possible
			if(canAutoconfigureDatastoreSize()) {
				super.writeTemporaryRedirect(ctx, "step4", TOADLET_URL+"?step="+WIZARD_STEP.MEMORY);
				return;
			}
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step4Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");
			
			bandwidthnfoboxHeader.addChild("#", l10n("datastoreSize"));
			bandwidthInfoboxContent.addChild("#", l10n("datastoreSizeLong"));
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "dsForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "ds");

			Option sizeOption = config.get("node").getOption("storeSize");
			if(!sizeOption.isDefault()) {
				long current = (Long)sizeOption.getValue();
				result.addChild("option", new String[] { "value", "selected" }, new String[] { SizeUtil.formatSize(current), "on" }, l10n("currentPrefix")+" "+SizeUtil.formatSize(current));
			}
			result.addChild("option", "value", "512M", "512MiB");
			result.addChild("option", "value", "1G", "1GiB");
			if(!sizeOption.isDefault())
				result.addChild("option", "value", "2G", "2GiB");
			else
				result.addChild("option", new String[] { "value", "selected" }, new String[] { "2G", "on" }, "2GiB");
			result.addChild("option", "value", "3G", "3GiB");
			result.addChild("option", "value", "5G", "5GiB");
			result.addChild("option", "value", "10G", "10GiB");
			result.addChild("option", "value", "20G", "20GiB");
			result.addChild("option", "value", "30G", "30GiB");
			result.addChild("option", "value", "50G", "50GiB");
			result.addChild("option", "value", "100G", "100GiB");
			
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "dsF", L10n.getString("FirstTimeWizardToadlet.continue")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		} else if(currentStep == WIZARD_STEP.MEMORY) {
			// FIXME: Get rid of it when the db4o branch is merged or auto-detect it (be careful of classpath's bug @see <freenet.Node>)
			// Attempt to skip one step if possible
			if(!WrapperConfig.canChangeProperties()) {
				super.writeTemporaryRedirect(ctx, "step6", TOADLET_URL+"?step="+WIZARD_STEP.CONGRATZ);
				return;
			}
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step6Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode memoryInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode memoryInfoboxHeader = memoryInfobox.addChild("div", "class", "infobox-header");
			HTMLNode memoryInfoboxContent = memoryInfobox.addChild("div", "class", "infobox-content");
			
			memoryInfoboxHeader.addChild("#", l10n("memoryLimit"));
			memoryInfoboxContent.addChild("#", l10n("memoryLimitLong"));
			
			HTMLNode bandwidthForm = ctx.addFormChild(memoryInfoboxContent, ".", "memoryForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "memory");
			result.addChild("option", "value", "64", l10n("memory.64M"));
			result.addChild("option", "value", "128", l10n("memory.128M"));
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "192", "selected" }, l10n("memory.192M"));
			result.addChild("option", "value", "256", l10n("memory.256M"));
			result.addChild("option", "value", "512", l10n("memory.512M"));

			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "memoryF", L10n.getString("FirstTimeWizardToadlet.continue")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;

		}else if(currentStep == WIZARD_STEP.CONGRATZ) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step7Title"), true, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode congratzInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode congratzInfoboxHeader = congratzInfobox.addChild("div", "class", "infobox-header");
			HTMLNode congratzInfoboxContent = congratzInfobox.addChild("div", "class", "infobox-content");

			congratzInfoboxHeader.addChild("#", l10n("congratz"));
			congratzInfoboxContent.addChild("p", l10n("congratzLong"));
			
			congratzInfoboxContent.addChild("a", "href", "?step="+WIZARD_STEP.FINAL, L10n.getString("FirstTimeWizardToadlet.continueEnd"));

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
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("homepageTitle"), false, ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		HTMLNode welcomeInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode welcomeInfoboxHeader = welcomeInfobox.addChild("div", "class", "infobox-header");
		HTMLNode welcomeInfoboxContent = welcomeInfobox.addChild("div", "class", "infobox-content");
		welcomeInfoboxHeader.addChild("#", l10n("welcomeInfoboxTitle"));
		
		HTMLNode firstParagraph = welcomeInfoboxContent.addChild("p");
		firstParagraph.addChild("#", l10n("welcomeInfoboxContent1"));
		HTMLNode secondParagraph = welcomeInfoboxContent.addChild("p");
		secondParagraph.addChild("a", "href", "?step="+WIZARD_STEP.BROWSER_WARNING).addChild("#", L10n.getString("FirstTimeWizardToadlet.clickContinue"));
		
		HTMLNode thirdParagraph = welcomeInfoboxContent.addChild("p");
		thirdParagraph.addChild("a", "href", "?step="+WIZARD_STEP.FINAL).addChild("#", l10n("skipWizard"));
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	private String l10nSec(String key) {
		return L10n.getString("SecurityLevels."+key);
	}

	private String l10nSec(String key, String pattern, String value) {
		return L10n.getString("SecurityLevels."+key, pattern, value);
	}

	@Override
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		String passwd = request.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
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
					HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("networkSecurityPageTitle"), ctx);
					HTMLNode content = ctx.getPageMaker().getContentNode(pageNode);
					HTMLNode formNode = ctx.addFormChild(content, ".", "configFormSecLevels");
					
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.networkThreatLevel", networkThreatLevel });
					HTMLNode infobox = formNode.addChild("div", "class", "infobox infobox-information");
					infobox.addChild("div", "class", "infobox-header", l10nSec("networkThreatLevelConfirmTitle", "mode", SecurityLevels.localisedName(newThreatLevel)));
					HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
					if(newThreatLevel == NETWORK_THREAT_LEVEL.MAXIMUM) {
						HTMLNode p = infoboxContent.addChild("p");
						L10n.addL10nSubstitution(p, "SecurityLevels.maximumNetworkThreatLevelWarning", new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
						p.addChild("#", " ");
						L10n.addL10nSubstitution(p, "SecurityLevels.maxSecurityYouNeedFriends", new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
						infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.networkThreatLevel.confirm", "off" }, l10nSec("maximumNetworkThreatLevelCheckbox"));
					} else /*if(newThreatLevel == NETWORK_THREAT_LEVEL.HIGH)*/ {
						HTMLNode p = infoboxContent.addChild("p");
						L10n.addL10nSubstitution(p, "FirstTimeWizardToadlet.highNetworkThreatLevelWarning", new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
						infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.networkThreatLevel.confirm", "off" }, l10n("highNetworkThreatLevelCheckbox"));
					}
					infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.networkThreatLevel.tryConfirm", "on" });
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
					formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", L10n.getString("ConfigToadlet.apply")});
					formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  L10n.getString("ConfigToadlet.reset")});
					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return;
				} else if((!request.isPartSet("security-levels.networkThreatLevel.confirm")) &&
						request.isPartSet("security-levels.networkThreatLevel.tryConfirm")) {
					super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_NETWORK);
					return;
				}
			}
			core.node.securityLevels.setThreatLevel(newThreatLevel);
			super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_FRIENDS);
			return;
		} else if(request.isPartSet("security-levels.friendsThreatLevel")) {
			// We don't require a confirmation here, since it's one page at a time, so there's less information to
			// confuse the user, and we don't know whether the node has friends yet etc.
			// FIXME should we have confirmation here???
			String friendsThreatLevel = request.getPartAsString("security-levels.friendsThreatLevel", 128);
			FRIENDS_THREAT_LEVEL newThreatLevel = SecurityLevels.parseFriendsThreatLevel(friendsThreatLevel);
			if(newThreatLevel == null) {
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_FRIENDS);
				return;
			}
			if((newThreatLevel == FRIENDS_THREAT_LEVEL.HIGH)) {
				if((!request.isPartSet("security-levels.friendsThreatLevel.confirm")) &&
					(!request.isPartSet("security-levels.friendsThreatLevel.tryConfirm"))) {
					HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("friendsSecurityPageTitle"), ctx);
					HTMLNode content = ctx.getPageMaker().getContentNode(pageNode);
					HTMLNode formNode = ctx.addFormChild(content, ".", "configFormSecLevels");
					
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.friendsThreatLevel", friendsThreatLevel });
					HTMLNode infobox = formNode.addChild("div", "class", "infobox infobox-information");
					infobox.addChild("div", "class", "infobox-header", l10nSec("friendsThreatLevelConfirmTitle", "mode", SecurityLevels.localisedName(newThreatLevel)));
					HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
					HTMLNode p = infoboxContent.addChild("p");
					L10n.addL10nSubstitution(p, "FirstTimeWizardToadlet.highFriendsThreatLevelWarning", new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
					infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.friendsThreatLevel.confirm", "off" }, l10nSec("highFriendsThreatLevelCheckbox"));
					infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.friendsThreatLevel.tryConfirm", "on" });
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
					formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", L10n.getString("ConfigToadlet.apply")});
					formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  L10n.getString("ConfigToadlet.reset")});
					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return;
				} else if((!request.isPartSet("security-levels.friendsThreatLevel.confirm")) &&
						request.isPartSet("security-levels.friendsThreatLevel.tryConfirm")) {
					super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_FRIENDS);
					return;
				}
			}
			core.node.securityLevels.setThreatLevel(newThreatLevel);
			super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_PHYSICAL);
			return;
		} else if(request.isPartSet("security-levels.physicalThreatLevel")) {
			// We don't require a confirmation here, since it's one page at a time, so there's less information to
			// confuse the user, and we don't know whether the node has friends yet etc.
			// FIXME should we have confirmation here???
			String physicalThreatLevel = request.getPartAsString("security-levels.physicalThreatLevel", 128);
			PHYSICAL_THREAT_LEVEL newThreatLevel = SecurityLevels.parsePhysicalThreatLevel(physicalThreatLevel);
			if(newThreatLevel == null) {
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_PHYSICAL);
				return;
			}
			core.node.securityLevels.setThreatLevel(newThreatLevel);
			core.storeConfig();
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
			_setUpstreamBandwidthLimit(request.getPartAsString("bw", 20));
			super.writeTemporaryRedirect(ctx, "step4", TOADLET_URL+"?step="+WIZARD_STEP.DATASTORE_SIZE);
			return;
		} else if(request.isPartSet("dsF")) {
			_setDatastoreSize(request.getPartAsString("ds", 20));
			super.writeTemporaryRedirect(ctx, "step5", TOADLET_URL+"?step="+WIZARD_STEP.MEMORY);
			return;
		} else if(request.isPartSet("memoryF")) {
			String selectedMemorySize = request.getPartAsString("memoryF", 6);
			
			int memorySize = Fields.parseInt(selectedMemorySize, -1);
			if(memorySize >= 0) {
				WrapperConfig.setWrapperProperty("wrapper.java.maxmemory", selectedMemorySize);
			}
			super.writeTemporaryRedirect(ctx, "step6", TOADLET_URL+"?step="+WIZARD_STEP.CONGRATZ);
			return;
		}
		
		super.writeTemporaryRedirect(ctx, "invalid/unhandled data", TOADLET_URL);
	}
	
	private String l10n(String key) {
		return L10n.getString("FirstTimeWizardToadlet."+key);
	}

	@Override
	public String supportedMethods() {
		return "GET, POST";
	}
	
	private void _setDatastoreSize(String selectedStoreSize) {
		try {
			config.get("node").set("storeSize", selectedStoreSize);
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
	
	private boolean canAutoconfigureBandwidth() {
		if(!config.get("node").getOption("outputBandwidthLimit").isDefault())
			return false;
		FredPluginBandwidthIndicator bwIndicator = core.node.ipDetector.getBandwidthIndicator();
		if(bwIndicator == null)
			return false;
		
		int downstreamBWLimit = bwIndicator.getDownstreamMaxBitRate();
		if(downstreamBWLimit > 0) {
			int bytes = (downstreamBWLimit / 8) - 1;
			String downstreamBWLimitString = SizeUtil.formatSize(bytes * 2 / 3);
			_setDownstreamBandwidthLimit(downstreamBWLimitString);
			Logger.normal(this, "The node has a bandwidthIndicator: it has reported downstream=" + downstreamBWLimit + "bits/sec... we will use " + downstreamBWLimitString + " and skip the bandwidth selection step of the wizard.");
		}
		
		// We don't mind if the downstreamBWLimit couldn't be set, but upstreamBWLimit is important
		int upstreamBWLimit = bwIndicator.getUpstramMaxBitRate();
		if(upstreamBWLimit > 0) {
			int bytes = (upstreamBWLimit / 8) - 1;
			String upstreamBWLimitString = (bytes < 16384 ? "8K" : SizeUtil.formatSize(bytes / 2));
			_setUpstreamBandwidthLimit(upstreamBWLimitString);
			Logger.normal(this, "The node has a bandwidthIndicator: it has reported upstream=" + upstreamBWLimit + "bits/sec... we will use " + upstreamBWLimitString + " and skip the bandwidth selection step of the wizard.");
			return true;
		}else
			return false;
	}
	
	private boolean canAutoconfigureDatastoreSize() {
		if(!config.get("node").getOption("storeSize").isDefault())
			return false;
		// Use JNI to find out the free space on this partition.
		long freeSpace = -1;
		File dir = FileUtil.getCanonicalFile(core.node.getNodeDir());
		try {
			Class<? extends File> c = dir.getClass();
			Method m = c.getDeclaredMethod("getFreeSpace", new Class<?>[0]);
			if(m != null) {
				Long lFreeSpace = (Long) m.invoke(dir, new Object[0]);
				if(lFreeSpace != null) {
					freeSpace = lFreeSpace.longValue();
					System.err.println("Found free space on node's partition: on " + dir + " = " + SizeUtil.formatSize(freeSpace));
				}
			}
		} catch(NoSuchMethodException e) {
			// Ignore
			freeSpace = -1;
		} catch(Throwable t) {
			System.err.println("Trying to access 1.6 getFreeSpace(), caught " + t);
			freeSpace = -1;
		}
		
		if(freeSpace <= 0)
			return false;
		else {
			String shortSize = null;
			if(freeSpace / 20 > 1024 * 1024 * 1024) { // 20GB+ => 5%, limit 256GB
				// If 20GB+ free, 5% of available disk space.
				// Maximum of 256GB. That's a 128MB bloom filter.
				shortSize = SizeUtil.formatSize(Math.min(freeSpace / 20, 256*1024*1024*1024L));
			}else if(freeSpace / 10 > 1024 * 1024 * 1024) { // 10GB+ => 10%
				// If 10GB+ free, 10% of available disk space.
				shortSize = SizeUtil.formatSize(freeSpace / 10);
			}else if(freeSpace / 5 > 1024 * 1024 * 1024) { // 5GB+ => 512MB
				// If 5GB+ free, default to 512MB
				shortSize = "512MB";
			}else // <5GB => 256MB
				shortSize = "256MB";
			
			_setDatastoreSize(shortSize);
			return true;
		}
	}
}
