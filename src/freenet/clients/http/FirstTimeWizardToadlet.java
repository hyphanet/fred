/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.EnumMap;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.wizardsteps.*;
import freenet.config.Config;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.HTTPRequest;

/**
 * A first time wizard aimed to ease the configuration of the node.
 *
 * TODO: a choose your CSS step?
 */
public class FirstTimeWizardToadlet extends Toadlet {
	private final NodeClientCore core;
	private final EnumMap<WIZARD_STEP, Step> steps;
	private final MISC stepMISC;
	private final SECURITY_NETWORK stepSECURITY_NETWORK;
	private final SECURITY_PHYSICAL stepSECURITY_PHYSICAL;
	private final BANDWIDTH stepBANDWIDTH;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public enum WIZARD_STEP {
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
		CONGRATZ
	}

	public enum WIZARD_PRESET {
		LOW,
		HIGH
	}

	FirstTimeWizardToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		//Generic Toadlet-related initialization.
		super(client);
		this.core = core;
		Config config = node.config;

		//Add step handlers that aren't set by presets
		steps = new EnumMap<WIZARD_STEP, Step>(WIZARD_STEP.class);
		steps.put(WIZARD_STEP.WELCOME, new WELCOME(config));
		steps.put(WIZARD_STEP.BROWSER_WARNING, new BROWSER_WARNING());
		steps.put(WIZARD_STEP.NAME_SELECTION, new NAME_SELECTION(config));
		steps.put(WIZARD_STEP.DATASTORE_SIZE, new DATASTORE_SIZE(core, config));
		steps.put(WIZARD_STEP.CONGRATZ, new CONGRATZ(config));
		steps.put(WIZARD_STEP.OPENNET, new OPENNET());

		//Add step handlers that are set by presets
		stepMISC = new MISC(core, config);
		steps.put(WIZARD_STEP.MISC, stepMISC);

		stepSECURITY_NETWORK = new SECURITY_NETWORK(core);
		steps.put(WIZARD_STEP.SECURITY_NETWORK, stepSECURITY_NETWORK);

		stepSECURITY_PHYSICAL = new SECURITY_PHYSICAL(core);
		steps.put(WIZARD_STEP.SECURITY_PHYSICAL, stepSECURITY_PHYSICAL);

		stepBANDWIDTH = new BANDWIDTH(core, config);
		steps.put(WIZARD_STEP.BANDWIDTH, stepBANDWIDTH);
	}

	public static final String TOADLET_URL = "/wizard/";

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}

		//Read the current step from the URL parameter, defaulting to the welcome page if unset or invalid..
		WIZARD_STEP currentStep;
		try {
			currentStep = WIZARD_STEP.valueOf(request.getParam("step", WIZARD_STEP.WELCOME.toString()));
		} catch (IllegalArgumentException e) {
			currentStep = WIZARD_STEP.WELCOME;
		}
		//Skip the browser warning page if using Chrome because its incognito mode works from command line.
		if (currentStep == WIZARD_STEP.BROWSER_WARNING &&
			request.getHeader("user-agent").contains("Chrome")) {
			StringBuilder redirectTo = new StringBuilder(TOADLET_URL+"?step=MISC");
			if (request.isParameterSet("preset")) {
				redirectTo.append("&preset=").append(WIZARD_PRESET.valueOf(request.getParam("preset")));
			}
			super.writeTemporaryRedirect(ctx, "Skipping unneeded warning", redirectTo.toString());
			return;
		} else if (currentStep == WIZARD_STEP.MISC && request.isParameterSet("preset")) {
			//If using a preset, skip the miscellaneous page as both high and low security set those settings.
			StringBuilder redirectTo = new StringBuilder(TOADLET_URL+"?step=");
			WIZARD_PRESET preset = WIZARD_PRESET.valueOf(request.getParam("preset"));
			if (preset == WIZARD_PRESET.HIGH) {
				redirectTo.append("SECURITY_NETWORK&preset=HIGH&confirm=true&opennet=false&security-levels.networkThreatLevel=HIGH");
			} else /*if (preset == WIZARD_PRESET.LOW)*/ {
				redirectTo.append("BANDWIDTH");
			}
			super.writeTemporaryRedirect(ctx, "Skipping to next necessary step", redirectTo.toString());
			return;
		} else if (currentStep == WIZARD_STEP.SECURITY_NETWORK && !request.isParameterSet("opennet")) {
			//If opennet isn't defined when attempting to set network security level, ask again.
			super.writeTemporaryRedirect(ctx, "Need opennet choice", TOADLET_URL+"?step=OPENNET");
			return;
		} else if (currentStep == WIZARD_STEP.BANDWIDTH && request.isParameterSet("preset") &&  stepBANDWIDTH.canSkip()) {
			//If using a preset and the bandwidth limits can be set automatically, skip the step.
			super.writeTemporaryRedirect(ctx, "Autodetected, not needed",
			        FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.DATASTORE_SIZE);
			return;
		}
		Step getStep = steps.get(currentStep);
		StepPageHelper helper = new StepPageHelper(ctx);
		getStep.getStep(request, helper);
		writeHTMLReply(ctx, 200, "OK", helper.getPageOuter().generate());
	}

	/**
	 * @return whether wizard steps should log minor events.
	 */
	public static boolean shouldLogMinor() {
		return logMINOR;
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}

		String passwd = request.getPartAsStringFailsafe("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(logMINOR) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
			super.writeTemporaryRedirect(ctx, "invalid/unhandled data", "/");
			return;
		}

		WIZARD_STEP currentStep;
		try {
			//Attempt to parse the current step, defaulting to WELCOME if unspecified or invalid.
			String currentValue = request.getPartAsStringFailsafe("step", 20);
			currentStep = currentValue.isEmpty() ? WIZARD_STEP.WELCOME : WIZARD_STEP.valueOf(currentValue);
		} catch (IllegalArgumentException e) {
			//Failed to parse enum value, default to welcome.
			//TODO: Should this be an error page instead?
			currentStep = WIZARD_STEP.WELCOME;
		}

		//Check the requested preset. Deal with it here so that WELCOME's POST doesn't have to have access to
		//settings modifiers in other steps.
		if (request.isPartSet("presetLow") || request.isPartSet("presetHigh") || request.isPartSet("presetNone")) {
			//Set up destination URL based on incognito and current preset.
			StringBuilder redirectTo = new StringBuilder(TOADLET_URL+"?step=BROWSER_WARNING&incognito=");
			redirectTo.append(request.getPartAsStringFailsafe("incognito", 5));

			/*If detailed setup was selected, just fall through to the full wizard and do not set
			 *the "preset" URL parameter.*/

			if (request.isPartSet("presetLow")) {
				//Low security preset
				stepSECURITY_NETWORK.setThreatLevel(SecurityLevels.NETWORK_THREAT_LEVEL.LOW);
				stepSECURITY_PHYSICAL.setThreatLevel(SecurityLevels.PHYSICAL_THREAT_LEVEL.NORMAL,
				        SecurityLevels.PHYSICAL_THREAT_LEVEL.LOW);
				stepMISC.setUPnP(true);
				stepMISC.setAutoUpdate(true);
				redirectTo.append("&preset="+WIZARD_PRESET.LOW);
			} else if (request.isPartSet("presetHigh")) {
				//High security preset
				redirectTo.append("&preset="+WIZARD_PRESET.HIGH);
			}
			super.writeTemporaryRedirect(ctx, "Wizard redirecting.", redirectTo.toString());
			return;
		}

		try {
			super.writeTemporaryRedirect(ctx, "Wizard redirecting.", steps.get(currentStep).postStep(request));
		} catch (IOException e) {
			String title;
			if (e.getMessage().equals("cantWriteNewMasterKeysFile")) {
				//Recognized as being unable to write to the master keys file.
				title = NodeL10n.getBase().getString("SecurityLevels.cantWriteNewMasterKeysFileTitle");
			} else {
				//Some other error.
				title = NodeL10n.getBase().getString("Toadlet.internalErrorPleaseReport");
			}

			//Very loud error message, with descriptive title and header if possible.
			StringBuilder msg = new StringBuilder("<html><head><title>").append(title).
			        append("</title></head><body><h1>").append(title).append("</h1><pre>");

			//Print stack trace.
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			pw.flush();
			msg.append(sw.toString()).append("</pre>");

			//Include internal exception if one exists.
			Throwable internal = e.getCause();
			if (internal != null) {
				msg.append("<h1>").
				        append(NodeL10n.getBase().getString("Toadlet.internalErrorPleaseReport")).
				        append("</h1>").append("<pre>");

				sw = new StringWriter();
				pw = new PrintWriter(sw);
				internal.printStackTrace(pw);
				pw.flush();
				msg.append(sw.toString()).append("</pre>");
			}
			msg.append("</body></html>");
			writeHTMLReply(ctx, 500, "Internal Error", msg.toString());
		}
	}

	@Override
	public String path() {
		return TOADLET_URL;
	}
}
