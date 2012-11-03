package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.*;
import freenet.support.api.HTTPRequest;

/**
 * Allows the user to set bandwidth limits with an emphasis on capping to a monthly total.
 */
public class BANDWIDTH_MONTHLY extends BandwidthManipulator implements Step {

	private static final long GB = 1000000000;
	/**
	 * Seconds in 30 days. Used for limit calculations.
	 */
	private static final double secondsPerMonth = 2592000d;
	/*
	 * Bandwidth used if both the upload and download limit are at the minimum. In GB. Assumes 24/7 uptime.
	 */
	private static final Double minCap = 2*Node.getMinimumBandwidth()*secondsPerMonth/GB;

	private static final long[] caps = { (long)Math.ceil(minCap), 50, 100, 150 };

	public BANDWIDTH_MONTHLY(NodeClientCore core, Config config) {
		super(core, config);
	}

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("bandwidthLimit"));

		if (request.isParameterSet("parseError")) {
			parseErrorBox(contentNode, helper, request.getParam("parseTarget"));
		}

		//Box for prettiness and explanation of function.
		HTMLNode infoBox = helper.getInfobox("infobox-normal", WizardL10n.l10n("bandwidthLimitMonthlyTitle"),
		        contentNode, null, false);
		NodeL10n.getBase().addL10nSubstitution(infoBox, "FirstTimeWizardToadlet.bandwidthLimitMonthly",
		        new String[] { "bold", "coreSettings" }, new HTMLNode[] { HTMLNode.STRONG, 
		                new HTMLNode("#", NodeL10n.getBase().getString("ConfigToadlet.node"))});

		//TODO: Might want to detect bandwidth limit and hide those too high to reach.
		//TODO: The user can always set a custom limit. At least one limit should be displayed in order to
		//TODO: demonstrate how to specify the limit, though.

		//Table header
		HTMLNode table = infoBox.addChild("table");
		HTMLNode headerRow = table.addChild("tr");
		headerRow.addChild("th", WizardL10n.l10n("bandwidthLimitMonthlyTitle"));
		headerRow.addChild("th", WizardL10n.l10n("bandwidthSelect"));

		//Row for each cap
		for (long cap : caps) {
			HTMLNode row = table.addChild("tr");
			//ISPs are likely to list limits in GB instead of GiB, so display GB here.
			row.addChild("td", String.valueOf(cap) +" GB");
			HTMLNode selectForm = helper.addFormChild(row.addChild("td"), ".", "limit");
			selectForm.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "hidden", "capTo", String.valueOf(cap)});
			selectForm.addChild("input",
			        new String[] { "type", "value" },
			        new String[] { "submit", WizardL10n.l10n("bandwidthSelect")});
		}

		//Row for custom entry
		HTMLNode customForm = helper.addFormChild(table.addChild("tr"), ".", "custom-form");
		HTMLNode capInput = customForm.addChild("td");
		capInput.addChild("input",
			new String[]{"type", "name"},
			new String[]{"text", "capTo"});
		capInput.addChild("#", " GB");
		customForm.addChild("td").addChild("input",
			new String[]{"type", "value"},
			new String[]{"submit", WizardL10n.l10n("bandwidthSelect")});

		HTMLNode backForm = helper.addFormChild(infoBox, ".", "backForm");
		backForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "back", NodeL10n.getBase().getString("Toadlet.back")});
	}

	@Override
	public String postStep(HTTPRequest request)  {
		double GBPerMonth;
		long bytesPerMonth;
		// capTo is specified as floating point GB.
		String capTo = request.getPartAsStringFailsafe("capTo", 4096);
		try {
			GBPerMonth = Double.valueOf(capTo);
			bytesPerMonth = Math.round(GBPerMonth * GB);
		} catch (NumberFormatException e) {
			StringBuilder target = new StringBuilder("BANDWIDTH_MONTHLY&parseError=true&parseTarget=");
			target.append(URLEncoder.encode(capTo, true));
			return target.toString();
		}
		/*
		 * Fraction of total limit used for download. Linear from 0.5 at the minimum cap to 0.8 at 100 GB.
		 *
		 * This is a line between (minCap, minFraction) and (maxScaleCap, maxFraction) which has been shifted
		 * to the left by minCap.
		 *
		 * This 50/50 split is consistent with the assumption in the definition of minCap that the upload and
		 * download limits are equal.
		 */
		final double minFraction = 0.5;
		final double maxFraction = 0.8;
		final double maxScaleCap = 100;
		assert minCap < maxScaleCap;
		double downloadFraction = ((maxFraction-minFraction)/(maxScaleCap - 2*minCap))*(GBPerMonth - minCap) + minFraction;
		// No minimum fraction clamp: caps less than the minimum get redirected to a warning instead.
		if (downloadFraction > maxFraction) downloadFraction = maxFraction;
		double bytesPerSecondTotal = bytesPerMonth/secondsPerMonth;
		String downloadLimit = String.valueOf(Math.ceil(bytesPerSecondTotal*downloadFraction));
		String uploadLimit = String.valueOf(Math.ceil(bytesPerSecondTotal*(1-downloadFraction)));

		try {
			setBandwidthLimit(downloadLimit, false);
		} catch (InvalidConfigValueException e) {
			StringBuilder target = new StringBuilder("BANDWIDTH_MONTHLY&parseError=true&parseTarget=");
			target.append(URLEncoder.encode(capTo, true));
			return target.toString();
		}

		try {
			setBandwidthLimit(uploadLimit, true);
		} catch (InvalidConfigValueException e) {
			StringBuilder target = new StringBuilder("BANDWIDTH_MONTHLY&parseError=true&parseTarget=");
			target.append(URLEncoder.encode(capTo, true));
			return target.toString();
		}

		setWizardComplete();

		return FirstTimeWizardToadlet.WIZARD_STEP.COMPLETE.name();
	}
}
