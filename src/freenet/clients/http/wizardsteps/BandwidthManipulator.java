package freenet.clients.http.wizardsteps;

import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.config.InvalidConfigValueException;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.support.HTMLNode;
import freenet.support.Logger;

import javax.swing.text.html.HTML;

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
	 * @throws freenet.config.InvalidConfigValueException If the value is negative or a number cannot be parsed from it.
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

	protected void parseErrorBox(HTMLNode parent, PageHelper helper, String parsingFailedOn) {
		HTMLNode infoBox = helper.getInfobox("infobox-warning", WizardL10n.l10n("bandwidthCouldNotParseTitle"),
		        parent, null, false);

		NodeL10n.getBase().addL10nSubstitution(infoBox, "FirstTimeWizardToadlet.bandwidthCouldNotParse",
		        new String[] { "limit" }, new HTMLNode[] { new HTMLNode("#", parsingFailedOn) });
	}

	/**
	 * Attempts to detect upstream and downstream bandwidth limits.
	 * @return Upstream and downstream bandwidth in bytes per second. If a limit is set to -1, it is unavailable or
	 * nonsensically low. In case of error, both values are set to:
	 * -2 if the upstream bandwidth setting has already been configured.
	 * -3 if the UPnP plugin is not loaded or done starting up.
	 */
	protected BandwidthLimit detectBandwidthLimits() {
		if (!config.get("node").getOption("outputBandwidthLimit").isDefault()) {
			return new BandwidthLimit(-2, -2);
		}
		FredPluginBandwidthIndicator bwIndicator = core.node.ipDetector.getBandwidthIndicator();
		if (bwIndicator == null) {
			Logger.normal(this, "The node does not have a bandwidthIndicator.");
			return new BandwidthLimit(-3, -3);
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

		return new BandwidthLimit(downstreamBytes, upstreamBytes);
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
