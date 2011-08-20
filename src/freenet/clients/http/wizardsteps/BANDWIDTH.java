package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.config.Option;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.api.HTTPRequest;

/**
 * Allows the user to select from pairs of upload and download bandwidth limits.
 */
public class BANDWIDTH implements Step {

	private final NodeClientCore core;
	private final Config config;
	/**
	 * 1 kibibyte (KiB). Value is in bits.
	 */
	private final int KiB = 8192;

	public BANDWIDTH(NodeClientCore core, Config config) {
		this.config = config;
		this.core = core;
	}

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		int autodetectedLimit = autoDetectBandwidthLimits();

		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("step3Title"));

		HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
		HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");

		bandwidthnfoboxHeader.addChild("#", WizardL10n.l10n("bandwidthLimit"));
		bandwidthInfoboxContent.addChild("#", WizardL10n.l10n("bandwidthLimitLong"));
		HTMLNode bandwidthForm = helper.addFormChild(bandwidthInfoboxContent, ".", "bwForm");
		HTMLNode result = bandwidthForm.addChild("select", "name", "bw");

		@SuppressWarnings("unchecked")
		Option<Integer> sizeOption = (Option<Integer>) config.get("node").getOption("outputBandwidthLimit");
		if(!sizeOption.isDefault()) {
			int current = sizeOption.getValue();
			result.addChild("option",
			        new String[] { "value", "selected" },
			        new String[] { SizeUtil.formatSize(current), "on" },
			                WizardL10n.l10n("currentSpeed")+" "+SizeUtil.formatSize(current)+"/s");
		} else if (autodetectedLimit > 0) {
			result.addChild("option",
			        new String[] { "value", "selected" },
			        new String[] { SizeUtil.formatSize(autodetectedLimit), "on" },
			                WizardL10n.l10n("autodetectedSuggestedLimit")+" "+SizeUtil.formatSize(autodetectedLimit)+"/s");
		}

		if(autodetectedLimit != -2) {
			result.addChild("option", "value", "8K", WizardL10n.l10n("bwlimitLowerSpeed"));
		}
		// Special case for 128kbps to increase performance at the cost of some link degradation. Above that we use 50% of the limit.
		result.addChild("option", "value", "12K", "512+/128 kbps (12KB/s)");
		if(autodetectedLimit != -1 || !sizeOption.isDefault()) {
			result.addChild("option", "value", "16K", "1024+/256 kbps (16KB/s)");
		} else {
			result.addChild("option",
			        new String[] { "value", "selected" },
			        new String[] { "16K", "selected" }, "1024+/256 kbps (16KB/s)");
		}
		result.addChild("option", "value", "32K", "1024+/512 kbps (32K/s)");
		result.addChild("option", "value", "64K", "1024+/1024 kbps (64K/s)");
		result.addChild("option", "value", "1000K", WizardL10n.l10n("bwlimitHigherSpeed"));

		bandwidthForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "bwF", WizardL10n.l10n("continue")});
		bandwidthForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
		bandwidthInfoboxContent.addChild("#", WizardL10n.l10n("bandwidthLimitAfter"));
	}

	@Override
	public String postStep(HTTPRequest request)  {
		// drop down options may be 6 chars or less, but formatted ones e.g. old value if re-running can be more
		String selectedUploadSpeed = request.getPartAsStringFailsafe("bw", 20);
		_setUpstreamBandwidthLimit(selectedUploadSpeed);
		return FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.DATASTORE_SIZE;
	}

	private void _setUpstreamBandwidthLimit(String selectedUploadSpeed) {
		try {
			config.get("node").set("outputBandwidthLimit", selectedUploadSpeed);
			Logger.normal(this, "The outputBandwidthLimit has been set to " + selectedUploadSpeed);
		} catch (ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	/**
	 * Checks if the bandwidth step can be skipped. Attempts to autodetect and autoconfigure upload and download
	 * bandwidth limits.
	 * @return true on success, and the step can be skipped. False on failure.
	 */
	public boolean canSkip() {
		int upstreamLimit = autoDetectBandwidthLimits();
		if (upstreamLimit > 0) {
			//Upstream limit is in bytes, as is the config value.
			_setUpstreamBandwidthLimit(""+upstreamLimit);
			return true;
		}
		return false;
	}

	/**
	 * Attempts to detect upstream and downstream bandwidth limits, setting the downstream limit if it is a positive value.
	 * @return -1 if the upload bandwidth setting is default, the detected downstream limit below 8KiB, or the detected
	 * upstream bandwidth is below 1 KiB. -2 if the detected upstream limit is below 16KiB.
	 * Otherwise, half the upload limit, as measured in is in bytes.
	 * 
	 */
	private int autoDetectBandwidthLimits() {
		if (!config.get("node").getOption("outputBandwidthLimit").isDefault()) {
			return -1;
		}
		FredPluginBandwidthIndicator bwIndicator = core.node.ipDetector.getBandwidthIndicator();
		if (bwIndicator == null) {
			Logger.normal(this, "The node does not have a bandwidthIndicator.");
			return -1;
		}

		int downstreamBWLimit = bwIndicator.getDownstreamMaxBitRate();
		int upstreamBWLimit = bwIndicator.getUpstramMaxBitRate();
		//Check for suspiciously low upstream bandwidth.
		if ((downstreamBWLimit > 0 && downstreamBWLimit < 8*KiB) || (upstreamBWLimit > 0 && upstreamBWLimit < KiB)) {
			// These are kilobits, not bits, per second, right?
			// Assume the router is buggy and don't autoconfigure.
			// Nothing that implements UPnP would be that slow.
			System.err.println("Buggy router? downstream: "+downstreamBWLimit+" upstream: "+upstreamBWLimit+" - these are supposed to be in bits per second!");
			return -1;
		}
		if (downstreamBWLimit > 0) {
			int bytes = (downstreamBWLimit / 8) - 1;
			String downstreamBWLimitString = SizeUtil.formatSize(bytes * 2 / 3);
			// Set the downstream limit anyway, it is usually so high as to be irrelevant.
			// The user can choose the upstream limit.
			_setDownstreamBandwidthLimit(downstreamBWLimitString);
			Logger.normal(this, "The node has a bandwidthIndicator: it has reported downstream=" + downstreamBWLimit + "bits/sec... we will use " + downstreamBWLimitString + " and skip the bandwidth selection step of the wizard.");
		}

		// We don't mind if the downstreamBWLimit couldn't be set, but upstreamBWLimit is important
		if (upstreamBWLimit > 0) {
			int bytes = upstreamBWLimit / 8;
			if (upstreamBWLimit < 16*KiB) return -2;
			return bytes / 2;
		} else {
			return -1;
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
}
