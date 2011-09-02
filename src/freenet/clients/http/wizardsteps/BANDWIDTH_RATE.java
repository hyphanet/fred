package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.URLEncoder;
import freenet.support.api.HTTPRequest;

/**
 * Allows the user to set bandwidth limits with an emphasis on limiting to certain download and upload rates.
 */
public class BANDWIDTH_RATE extends BandwidthManipulator implements Step {

	private final BandwidthLimit[] limits;

	public BANDWIDTH_RATE(NodeClientCore core, Config config) {
		super(core, config);
		final int KiB = 1024;
		limits = new BandwidthLimit[] {
		        new BandwidthLimit(32*KiB, 8*KiB),
		        new BandwidthLimit(64*KiB, 16*KiB),
		        new BandwidthLimit(128*KiB, 32*KiB),
		        new BandwidthLimit(256*KiB, 64*KiB),
		        new BandwidthLimit(512*KiB, 128*KiB)};
	}

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("bandwidthLimit"));

		if (request.isParameterSet("parseError")) {
			parseErrorBox(contentNode, helper, request.getParam("parseTarget"));
		}

		HTMLNode infoBox = helper.getInfobox("infobox-normal", WizardL10n.l10n("bandwidthLimitRateTitle"),
		        contentNode, null, false);
		NodeL10n.getBase().addL10nSubstitution(infoBox, "FirstTimeWizardToadlet.bandwidthLimitRate",
		        new String[] { "bold", "coreSettings" }, new HTMLNode[] { HTMLNode.STRONG, 
		                new HTMLNode("#", NodeL10n.getBase().getString("ConfigToadlet.node"))});

		//Table header
		HTMLNode table = infoBox.addChild("table");
		HTMLNode headerRow = table.addChild("tr");
		headerRow.addChild("th", WizardL10n.l10n("bandwidthDownloadHeader"));
		headerRow.addChild("th", WizardL10n.l10n("bandwidthUploadHeader"));
		headerRow.addChild("th", WizardL10n.l10n("bandwidthSelect"));

		BandwidthLimit detected = detectBandwidthLimits();
		if (detected.downBytes > 0 && detected.upBytes > 0) {
			//Detected limits reasonable; add half of both as recommended option.
			BandwidthLimit usable = new BandwidthLimit(detected.downBytes/2, detected.upBytes/2);
			addLimitRow(table, helper, usable, true);
		}

		for (BandwidthLimit limit : limits) {
			addLimitRow(table, helper, limit, false);
		}

		//Add custom option.
		HTMLNode customForm = helper.addFormChild(table.addChild("tr"), ".", "custom-limit");
		customForm.addChild("td").addChild("input",
		        new String[] { "type", "name" },
		        new String[] { "text", "customDown" });
		customForm.addChild("td").addChild("input",
		        new String[] { "type", "name" },
		        new String[] { "text", "customUp" });
		customForm.addChild("td").addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "customSelect", WizardL10n.l10n("bandwidthSelect")});

		HTMLNode backForm = helper.addFormChild(infoBox, ".", "backForm");
		backForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "back", NodeL10n.getBase().getString("Toadlet.back")});
	}

	@Override
	public String postStep(HTTPRequest request)  {

		//Custom limit given
		if (request.isPartSet("customSelect")) {
			String down = request.getPartAsStringFailsafe("customDown", 20);
			String up = request.getPartAsStringFailsafe("customUp", 20);
			//Remove per second indicator so that it can be parsed.
			down = down.replace(WizardL10n.l10n("bandwidthPerSecond"), "");
			up = up.replace(WizardL10n.l10n("bandwidthPerSecond"), "");

			String failedLimits = attemptSet(up, down);

			if (!failedLimits.isEmpty()) {
				//Some at least one limit failed to parse.
				return FirstTimeWizardToadlet.TOADLET_URL+"?step=BANDWIDTH_RATE&parseError=true&parseTarget="+
				        URLEncoder.encode(failedLimits, true);
			}

			//Success
			setWizardComplete();
			return "/?step=COMPLETE";
		}

		//Pre-defined limit selected.
		String preset = attemptSet(request.getPartAsStringFailsafe("upBytes", 20),
		        request.getPartAsStringFailsafe("downBytes", 20));

		if(!preset.isEmpty()) {
			//Error parsing predefined limit.
			//This should not happen, as there are no units to confound the parser.
			Logger.error(this, "Failed to parse pre-defined limit! Please report.");
			return FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH_RATE+"&parseError=true&parseTarget="+
				        URLEncoder.encode(preset, true);
		}

		setWizardComplete();
		return FirstTimeWizardToadlet.WIZARD_STEP.COMPLETE.name();
	}

	/**
	 * Attempts to set bandwidth limits.
	 * @param up output limit
	 * @param down input limit
	 * @return a comma-separated string of any failing limits. If both are successful, an empty string.
	 */
	private String attemptSet(String up, String down) {
		String failedLimits = "";
		try {
			setBandwidthLimit(down, false);
		} catch (InvalidConfigValueException e) {
			failedLimits = down;
		}
		try {
			setBandwidthLimit(up, true);
		} catch (InvalidConfigValueException e) {
			//Comma separated if both limits failed.
			failedLimits += failedLimits.isEmpty() ? up : ", "+up;
		}
		return failedLimits;
	}

	/**
	 * Adds a row to the table for the given limit. Adds download limit, upload limit, and selection button.
	 * @param table Table to add a row to.
	 * @param helper To make a form for the button and hidden fields.
	 * @param limit Limit to display.
	 * @param recommended Whether to mark the limit with (Recommended) next to the select button.
	 */
	private void addLimitRow(HTMLNode table, PageHelper helper, BandwidthLimit limit, boolean recommended) {
		HTMLNode row = table.addChild("tr");
		row.addChild("td", SizeUtil.formatSize(limit.downBytes)+WizardL10n.l10n("bandwidthPerSecond"));
		row.addChild("td", SizeUtil.formatSize(limit.upBytes)+WizardL10n.l10n("bandwidthPerSecond"));

		HTMLNode buttonCell = row.addChild("td");
		HTMLNode form = helper.addFormChild(buttonCell, ".", "limit");
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "hidden", "downBytes", String.valueOf(limit.downBytes)});
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "hidden", "upBytes", String.valueOf(limit.upBytes)});
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "select", WizardL10n.l10n("bandwidthSelect")});
		if (recommended) {
			buttonCell.addChild("#", WizardL10n.l10n("autodetectedSuggestedLimit"));
		}
	}
}
