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

	/**
	 * 1 gigabyte in bytes.
	 */
	private static final long GB = 1000000000;
	/**
	 * Seconds in 30 days. Used for limit calculations.
	 */
	private static final double secondsPerMonth = 2592000d;
	/*
	 * Bandwidth used if both the upload and download limit are at the minimum. In GB. Assumes 24/7 uptime.
	 */
	private static final Double minCap = 2*Node.getMinimumBandwidth()*secondsPerMonth/GB;

	private static final long[] caps = { (long)Math.ceil(minCap), 100, 150, 250, 500 };

	public BANDWIDTH_MONTHLY(NodeClientCore core, Config config) {
		super(core, config);
	}

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("bandwidthLimit"));

		// Check for and display any errors.
		final String parseTarget = request.getParam("parseTarget");
		if (request.isParameterSet("parseError")) {
			parseErrorBox(contentNode, helper, WizardL10n.l10n("bandwidthCouldNotParse", "limit", parseTarget));
		} else if (request.isParameterSet("tooLow")) {
			HTMLNode errorBox = parseErrorBox(contentNode, helper, WizardL10n.l10n("bandwidthMonthlyLow",
			                                  new String[] { "requested", "minimum", "useMinimum" },
			                                  new String[] { parseTarget, String.valueOf(Math.round(minCap)), WizardL10n.l10n("bandwidthMonthlyUseMinimum")}));

			HTMLNode minimumForm = helper.addFormChild(errorBox, ".", "use-minimum");
			minimumForm.addChild("input",
				new String[]{"type", "name", "value"},
				new String[]{"hidden", "capTo", String.valueOf(minCap)});
			minimumForm.addChild("input",
				new String[]{"type", "value"},
				new String[]{"submit", WizardL10n.l10n("bandwidthMonthlyUseMinimum")});
		}

		// Explain this step's operation.
		HTMLNode infoBox = helper.getInfobox("infobox-normal", WizardL10n.l10n("bandwidthLimitMonthlyTitle"),
		        contentNode, null, false);
		NodeL10n.getBase().addL10nSubstitution(infoBox, "FirstTimeWizardToadlet.bandwidthLimitMonthly",
		        new String[] { "bold", "coreSettings" }, new HTMLNode[] { HTMLNode.STRONG, 
		                new HTMLNode("#", NodeL10n.getBase().getString("ConfigToadlet.node"))});

		//TODO: Might want to detect bandwidth limit and hide those too high to reach.
		//TODO: The user can always set a custom limit. At least one limit should be displayed in order to
		//TODO: demonstrate how to specify the limit, though.

		// Table header
		HTMLNode table = infoBox.addChild("table");
		HTMLNode headerRow = table.addChild("tr");
		headerRow.addChild("th", WizardL10n.l10n("bandwidthLimitMonthlyTitle"));
		headerRow.addChild("th", WizardL10n.l10n("bandwidthSelect"));

		// Row for each cap
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

		// Row for custom entry
		HTMLNode customForm = helper.addFormChild(table.addChild("tr"), ".", "custom-form");
		HTMLNode capInput = customForm.addChild("td");
		capInput.addChild("input",
			new String[]{"type", "name"},
			new String[]{"text", "capTo"});
		capInput.addChild("#", " GB");
		customForm.addChild("td").addChild("input",
			new String[]{"type", "value"},
			new String[]{"submit", WizardL10n.l10n("bandwidthSelect")});

		// Back / next buttons
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
		// Target for an error page.
		StringBuilder target = new StringBuilder(FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH_MONTHLY.name()).append("&parseTarget=");
		try {
			GBPerMonth = Double.valueOf(capTo);
			bytesPerMonth = Math.round(GBPerMonth * GB);
		} catch (NumberFormatException e) {
			target.append(URLEncoder.encode(capTo, true));
			target.append("&parseError=true");
			return target.toString();
		}
		/*
		 * Fraction of total limit used for download. Asymptotically from 0.5 at the minimum cap to 0.8.
		 * 
		 * FIXME: Why do we do this? It does not actually work, since
		 * download cannot be larger than upload for any long amount
		 * of time.
		 *
		 * This 50/50 split is consistent with the assumption in the definition of minCap that the upload and
		 * download limits are equal.
		 */
		double bytesPerSecond = bytesPerMonth/secondsPerMonth;
		double minBytesPerSecond = Node.getMinimumBandwidth();
		double bwinc = bytesPerSecond - 2*minBytesPerSecond; // min for up and min for down
		double asymptoticDlFraction = 4. / 5.;
		double dllimit = minBytesPerSecond + (bwinc * asymptoticDlFraction);
		double ullimit = minBytesPerSecond + (bwinc * (1 - asymptoticDlFraction));
		String downloadLimit = String.valueOf(Math.ceil(dllimit));
		String uploadLimit = String.valueOf(Math.ceil(ullimit));

		try {
			setBandwidthLimit(downloadLimit, false);
			setBandwidthLimit(uploadLimit, true);
		} catch (InvalidConfigValueException e) {
			target.append(URLEncoder.encode(String.valueOf(GBPerMonth), true));
			target.append("&tooLow=true");
			return target.toString();
		}

		setWizardComplete();

		return FirstTimeWizardToadlet.WIZARD_STEP.COMPLETE.name();
	}
}
