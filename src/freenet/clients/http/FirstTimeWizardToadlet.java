/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumMap;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.wizardsteps.*;
import freenet.config.Config;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.HTTPRequest;

/**
 * A first time wizard aimed to ease the configuration of the node.
 */
public class FirstTimeWizardToadlet extends Toadlet {
	private final NodeClientCore core;
	private final EnumMap<WIZARD_STEP, Step> steps;
	private final MISC stepMISC;
	private final SECURITY_NETWORK stepSECURITY_NETWORK;
	private final SECURITY_PHYSICAL stepSECURITY_PHYSICAL;

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
		DATASTORE_SIZE,
		BANDWIDTH,
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
		steps.put(WIZARD_STEP.OPENNET, new OPENNET());
		steps.put(WIZARD_STEP.BANDWIDTH, new BANDWIDTH(core, config));

		//Add step handlers that are set by presets
		stepMISC = new MISC(core, config);
		steps.put(WIZARD_STEP.MISC, stepMISC);

		stepSECURITY_NETWORK = new SECURITY_NETWORK(core);
		steps.put(WIZARD_STEP.SECURITY_NETWORK, stepSECURITY_NETWORK);

		stepSECURITY_PHYSICAL = new SECURITY_PHYSICAL(core);
		steps.put(WIZARD_STEP.SECURITY_PHYSICAL, stepSECURITY_PHYSICAL);
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

		PersistFields persistFields = new PersistFields(request);

		//Skip the browser warning page if using Chrome because its incognito mode works from command line.
		if (currentStep == WIZARD_STEP.BROWSER_WARNING && request.getHeader("user-agent").contains("Chrome")) {
			super.writeTemporaryRedirect(ctx, "Skipping unneeded warning",
			        addPersistFields(TOADLET_URL+"?step=MISC", persistFields));
			return;
		} else if (currentStep == WIZARD_STEP.MISC && persistFields.isUsingPreset()) {
			/*If using a preset, skip the miscellaneous page as both high and low security set those settings.
			 * This overrides the persistence fields.*/
			StringBuilder redirectBase = new StringBuilder(TOADLET_URL+"?step=");
			if (persistFields.preset == WIZARD_PRESET.HIGH) {
				redirectBase.append("SECURITY_NETWORK&preset=HIGH&confirm=true&opennet=false&security-levels.networkThreatLevel=HIGH");
			} else /*if (persistFields.preset == WIZARD_PRESET.LOW)*/ {
				redirectBase.append("DATASTORE_SIZE&preset=LOW&opennet=true");
			}
			//addPersistFields() is not used here because the fields are overridden.
			super.writeTemporaryRedirect(ctx, "Skipping to next necessary step", redirectBase.toString());
			return;
		} else if (currentStep == WIZARD_STEP.SECURITY_NETWORK && !request.isParameterSet("opennet")) {
			//If opennet isn't defined when attempting to set network security level, ask again.
			super.writeTemporaryRedirect(ctx, "Need opennet choice",
			        addPersistFields(TOADLET_URL+"?step=OPENNET", persistFields));
			return;
		}

		Step getStep = steps.get(currentStep);
		PageHelper helper = new PageHelper(ctx, persistFields, currentStep);
		getStep.getStep(request, helper);
		writeHTMLReply(ctx, 200, "OK", helper.getPageOuter().generate());
	}

	/**
	 * @return whether wizard steps should log minor events.
	 */
	public static boolean shouldLogMinor() {
		return logMINOR;
	}

	/**
	 * Appends any defined persistence fields to the given URL.
	 * @param baseURL The URL to append fields to.
	 * @param persistFields To get persistence fields from.
	 * @return URL with persistence fields included.
	 */
	private static String addPersistFields(String baseURL, PersistFields persistFields) {
		StringBuilder url = new StringBuilder(baseURL).append("&opennet=").append(persistFields.opennet);
		if (persistFields.isUsingPreset()) {
			url.append("&preset=").append(persistFields.preset);
		}
		return url.toString();
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

		//User chose cancel, return to first page.
		if (request.isPartSet("cancel")) {
			currentStep = WIZARD_STEP.WELCOME;
		}

		PersistFields persistFields = new PersistFields(request);

		String redirectTarget;

		//If a preset was selected from the welcome page, apply preset settings and proceed to the browser warning.
		if (currentStep.equals(WIZARD_STEP.WELCOME) &&
		        (request.isPartSet("presetLow") || request.isPartSet("presetHigh") || request.isPartSet("presetNone"))) {
			/*Apply presets and persist incognito to the browser warning. The browser warning only checks
			  whether the parameter is set; not its value. UPnP is enabled first to allow it time to
			  load (and thus enable autodetection) before hitting the bandwidth page.*/
			StringBuilder redirectTo = new StringBuilder(TOADLET_URL+"?step=BROWSER_WARNING&incognito=");
			redirectTo.append(request.getPartAsStringFailsafe("incognito", 5));

			//Translate button name to preset value on the query string.
			if (request.isPartSet("presetLow")) {
				//Low security preset
				stepMISC.setUPnP(true);
				stepMISC.setAutoUpdate(true);
				redirectTo.append("&preset=LOW");
				stepSECURITY_NETWORK.setThreatLevel(SecurityLevels.NETWORK_THREAT_LEVEL.LOW);
				stepSECURITY_PHYSICAL.setThreatLevel(SecurityLevels.PHYSICAL_THREAT_LEVEL.NORMAL,
					SecurityLevels.PHYSICAL_THREAT_LEVEL.LOW);
			} else if (request.isPartSet("presetHigh")) {
				//High security preset
				stepMISC.setUPnP(true);
				stepMISC.setAutoUpdate(true);
				redirectTo.append("&preset=HIGH");
			}

			redirectTarget = redirectTo.toString();
		} else {
			try {
				redirectTarget = steps.get(currentStep).postStep(request);

				//Opennet step can change the persisted value for opennet.
				if (currentStep == WIZARD_STEP.OPENNET) {
					try {
						HTTPRequest newRequest = new HTTPRequestImpl(new URI(redirectTarget), "GET");
						redirectTarget = TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_NETWORK;
						persistFields = new PersistFields(persistFields.preset, newRequest);
					} catch (URISyntaxException e) {
						Logger.error(this, "Unexpected invalid query string from OPENNET step! "+e, e);
						redirectTarget = TOADLET_URL+"?step="+WIZARD_STEP.WELCOME;
					}

				}
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
				return;
			}
		}
		super.writeTemporaryRedirect(ctx, "Wizard redirecting.", addPersistFields(redirectTarget, persistFields));
	}

	@Override
	public String path() {
		return TOADLET_URL;
	}
}
