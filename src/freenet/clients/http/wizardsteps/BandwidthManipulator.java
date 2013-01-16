package freenet.clients.http.wizardsteps;

import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.config.InvalidConfigValueException;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.support.HTMLNode;
import freenet.support.Logger;

/**
 * Utility class used by bandwidth steps to detect and set bandwidth, and set the wizard completion flag.
 */
public abstract class BandwidthManipulator {

	protected final NodeClientCore core;
	protected final Config config;

	public BandwidthManipulator(NodeClientCore core, Config config) {
		this.config = config;
		this.core = core;
	}

	/**
	 * Sets the selected limit type to the given limit.
	 * @param limit To parse limit from. Can include SI or IEC units, but not /s.
	 * @param setOutputLimit If true, output limit is set. If false, input limit is set.
	 * @throws freenet.config.InvalidConfigValueException If the value is negative, a number cannot be parsed from it, or the value is too low to be usable.
	 * @see freenet.node.Node#minimumBandwidth
	 */
	protected void setBandwidthLimit (String limit, boolean setOutputLimit) throws InvalidConfigValueException {
		String limitType = setOutputLimit ? "outputBandwidthLimit" : "inputBandwidthLimit";
		try {
			config.get("node").set(limitType, limit);
			Logger.normal(this, "The " + limitType + " has been set to " + limit);
		} catch (ConfigException e) {
			if (e instanceof InvalidConfigValueException) {
				//Limit was not readable.
				throw (InvalidConfigValueException)e;
			}
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	/**
	 * Creates a titled infobox for a bandwidth setting error.
	 *
	 * @param parent Node to attach warning to.
	 * @param helper Helper to create infobox.
	 * @param message Message to display in the infobox body.
	 *
	 * @return infobox node with the message added.
	 */
	protected HTMLNode parseErrorBox(HTMLNode parent, PageHelper helper, String message) {
		HTMLNode infoBox = helper.getInfobox("infobox-warning", WizardL10n.l10n("bandwidthErrorSettingTitle"),
		        parent, null, false);

		infoBox.addChild("p", message);

		return infoBox;
	}

	protected BandwidthLimit getCurrentBandwidthLimitsOrNull() {
		if (!config.get("node").getOption("outputBandwidthLimit").isDefault()) {
			return new BandwidthLimit(core.node.getInputBandwidthLimit(), core.node.getOutputBandwidthLimit(), "bandwidthCurrent", false);
		}
		return null;
	}
	
	/**
	 * Attempts to detect upstream and downstream bandwidth limits.
	 * @return Upstream and downstream bandwidth in bytes per second. If a limit is set to -1, it is unavailable or
	 * nonsensically low. In case of error, both values are set to:
	 * -2 if the upstream bandwidth setting has already been configured.
	 * -3 if the UPnP plugin is not loaded or done starting up.
	 */
	protected BandwidthLimit detectBandwidthLimits() {
		FredPluginBandwidthIndicator bwIndicator = core.node.ipDetector.getBandwidthIndicator();
		if (bwIndicator == null) {
			Logger.normal(this, "The node does not have a bandwidthIndicator.");
			return new BandwidthLimit(-3, -3, "bandwidthDetected", false);
		}

		int downstreamBits = bwIndicator.getDownstreamMaxBitRate();
		int upstreamBits = bwIndicator.getUpstramMaxBitRate();
		Logger.normal(this, "bandwidthIndicator reports downstream " + downstreamBits + " bits/s and upstream "+upstreamBits+" bits/s.");

		int downstreamBytes, upstreamBytes;

		//For readability, in bits.
		final int KiB = 8192;

		if (downstreamBits < 0) {
			//Reported unavailable.
			downstreamBytes = -1;
		} else if (downstreamBits < 8*KiB) {
			//Nonsensically slow.
			System.err.println("Detected downstream of "+downstreamBits+" bits/s is nonsensically slow, ignoring.");
			downstreamBytes = -1;
		} else {
			downstreamBytes = downstreamBits/8;
		}

		if (upstreamBits < 0) {
			//Reported unavailable..
			upstreamBytes = -1;
		} else if (upstreamBits < KiB) {
			//Nonsensically slow.
			System.err.println("Detected upstream of "+upstreamBits+" bits/s is nonsensically slow, ignoring.");
			upstreamBytes = -1;
		} else {
			upstreamBytes = upstreamBits/8;
		}

		return new BandwidthLimit(downstreamBytes, upstreamBytes, "bandwidthDetected", false);
	}

	protected void setWizardComplete() {
		//Set wizard completion flag
		try {
			config.get("fproxy").set("hasCompletedWizard", true);
			config.store();
		} catch (ConfigException e) {
			//TODO: Is there anything that can reasonably be done about this? What kind of failures could occur?
			//TODO: Is logging and continuing a reasonable behavior?
			Logger.error(this, e.getMessage(), e);
		}
	}
}
