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
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.WrapperConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
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
		OPENNET,
		NAME_SELECTION,
		BANDWIDTH,
		DATASTORE_SIZE,
		MEMORY,
		CONGRATZ;
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
		
		if(currentStep == WIZARD_STEP.OPENNET) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step1Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode opennetInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode opennetInfoboxHeader = opennetInfobox.addChild("div", "class", "infobox-header");
			HTMLNode opennetInfoboxContent = opennetInfobox.addChild("div", "class", "infobox-content");
			
			opennetInfoboxHeader.addChild("#", l10n("connectToStrangers"));
			opennetInfoboxContent.addChild("p", l10n("connectToStrangersLong"));
			opennetInfoboxContent.addChild("p", l10n("enableOpennet"));
			HTMLNode opennetForm = ctx.addFormChild(opennetInfoboxContent, ".", "opennetForm");
			HTMLNode opennetDiv = opennetForm.addChild("div", "class", "opennetDiv");
			opennetDiv.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "enableOpennet", "true" }, l10n("opennetYes"));
			opennetDiv.addChild("br");
			opennetDiv.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "enableOpennet", "false" }, l10n("opennetNo"));
			opennetForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "opennetF", L10n.getString("FirstTimeWizardToadlet.continue")});
			opennetForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
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
			
			// don't forget to update handlePost too if you change that!
			result.addChild("option", "value", "8K", l10n("bwlimitLowerSpeed"));
			// Special case for 128kbps to increase performance at the cost of some link degradation. Above that we use 50% of the limit.
			result.addChild("option", "value", "12K", "512+/128 kbps");
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "16K", "selected" }, "1024+/256 kbps");
			result.addChild("option", "value", "32K", "1024+/512 kbps");
			result.addChild("option", "value", "64K", "1024+/1024 kbps");
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

			result.addChild("option", "value", "2G", "2GiB");
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
			
			congratzInfoboxContent.addChild("a", "href", "/", L10n.getString("FirstTimeWizardToadlet.continueEnd"));

			this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
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
		secondParagraph.addChild("a", "href", "?step="+WIZARD_STEP.OPENNET).addChild("#", L10n.getString("FirstTimeWizardToadlet.clickContinue"));
		
		HTMLNode thirdParagraph = welcomeInfoboxContent.addChild("p");
		thirdParagraph.addChild("a", "href", "/").addChild("#", l10n("skipWizard"));
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
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
		
		
		if(request.isPartSet("enableOpennet")) {
			String isOpennetEnabled = request.getPartAsString("enableOpennet", 255);
			boolean enable;
			try {
				enable = Fields.stringToBool(isOpennetEnabled);
			} catch (NumberFormatException e) {
				Logger.error(this, "Invalid opennetEnabled: "+isOpennetEnabled, e);
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.OPENNET);
				return;
			}
			try {
				config.get("node.opennet").set("enabled", enable);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen setting opennet.enabled=" + enable + " please report: " + e, e);
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.OPENNET);
				return;
			} catch (NodeNeedRestartException e) {
				Logger.error(this, "Should not happen setting opennet.enabled=" + enable + " please report: " + e, e);
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL + "?step=" + WIZARD_STEP.OPENNET);
				return;
			}
			super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.NAME_SELECTION+"&opennet="+enable);
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
			_setUpstreamBandwidthLimit(request.getPartAsString("bw", 6));
			super.writeTemporaryRedirect(ctx, "step4", TOADLET_URL+"?step="+WIZARD_STEP.DATASTORE_SIZE);
			return;
		} else if(request.isPartSet("dsF")) {
			_setDatastoreSize(request.getPartAsString("ds", 6));
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
		// Use JNI to find out the free space on this partition.
		long freeSpace = -1;
		File dir = FileUtil.getCanonicalFile(core.node.getNodeDir());
		try {
			Class c = dir.getClass();
			Method m = c.getDeclaredMethod("getFreeSpace", new Class[0]);
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
			if(freeSpace / 20 > 1024 * 1024 * 1024) {
				// If 20GB+ free, 5% of available disk space.
				shortSize = SizeUtil.formatSize(freeSpace / 20);
			}else if(freeSpace / 10 > 1024 * 1024 * 1024) {
				// If 10GB+ free, 10% of available disk space.
				shortSize = SizeUtil.formatSize(freeSpace / 10);
			}else if(freeSpace / 5 > 1024 * 1024 * 1024) {
				// If 5GB+ free, default to 512MB
				shortSize = "512MB";
			}else
				shortSize = "256MB";
			
			_setDatastoreSize(shortSize);
			return true;
		}
	}
}
