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
import freenet.clients.http.wizardsteps.BANDWIDTH;
import freenet.clients.http.wizardsteps.BANDWIDTH_MONTHLY;
import freenet.clients.http.wizardsteps.BANDWIDTH_RATE;
import freenet.clients.http.wizardsteps.BROWSER_WARNING;
import freenet.clients.http.wizardsteps.DATASTORE_SIZE;
import freenet.clients.http.wizardsteps.MISC;
import freenet.clients.http.wizardsteps.NAME_SELECTION;
import freenet.clients.http.wizardsteps.OPENNET;
import freenet.clients.http.wizardsteps.PageHelper;
import freenet.clients.http.wizardsteps.PersistFields;
import freenet.clients.http.wizardsteps.SECURITY_NETWORK;
import freenet.clients.http.wizardsteps.SECURITY_PHYSICAL;
import freenet.clients.http.wizardsteps.Step;
import freenet.clients.http.wizardsteps.WELCOME;
import freenet.config.Config;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels.NetworkThreatLevel;
import freenet.node.SecurityLevels.PhysicalThreatLevel;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.HTTPRequest;

/**
 * A first time wizard aimed to ease the configuration of the node.
 */
public class FirstTimeWizardToadlet extends Toadlet {
	private final NodeClientCore core;
	private final EnumMap<WizardStep, Step> steps;
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

	public enum WizardStep {
		WELCOME,
		BROWSER_WARNING,
		MISC,
		OPENNET,
		SECURITY_NETWORK,
		SECURITY_PHYSICAL,
		NAME_SELECTION,
		DATASTORE_SIZE,
		BANDWIDTH,
		BANDWIDTH_MONTHLY,
		BANDWIDTH_RATE,
		COMPLETE //Redirects to front page
	}

	public enum WizardPreset {
		LOW,
		HIGH
	}

	FirstTimeWizardToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		//Generic Toadlet-related initialization.
		super(client);
		this.core = core;
		Config config = node.config;

		//Add step handlers that aren't set by presets
		steps = new EnumMap<WizardStep, Step>(WizardStep.class);
		steps.put(WizardStep.WELCOME, new WELCOME(config));
		steps.put(WizardStep.BROWSER_WARNING, new BROWSER_WARNING());
		steps.put(WizardStep.NAME_SELECTION, new NAME_SELECTION(config));
		steps.put(WizardStep.DATASTORE_SIZE, new DATASTORE_SIZE(core, config));
		steps.put(WizardStep.OPENNET, new OPENNET());
		steps.put(WizardStep.BANDWIDTH, new BANDWIDTH());
		steps.put(WizardStep.BANDWIDTH_MONTHLY, new BANDWIDTH_MONTHLY(core, config));
		steps.put(WizardStep.BANDWIDTH_RATE, new BANDWIDTH_RATE(core, config));

		//Add step handlers that are set by presets
		stepMISC = new MISC(core, config);
		steps.put(WizardStep.MISC, stepMISC);

		stepSECURITY_NETWORK = new SECURITY_NETWORK(core);
		steps.put(WizardStep.SECURITY_NETWORK, stepSECURITY_NETWORK);

		stepSECURITY_PHYSICAL = new SECURITY_PHYSICAL(core);
		steps.put(WizardStep.SECURITY_PHYSICAL, stepSECURITY_PHYSICAL);
	}

	public static final String TOADLET_URL = "/wizard/";

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
        if(!ctx.checkFullAccess(this))
            return;

		//Read the current step from the URL parameter, defaulting to the welcome page if unset or invalid..
		WizardStep currentStep;
		try {
			currentStep = WizardStep.valueOf(request.getParam("step", WizardStep.WELCOME.toString()));
		} catch (IllegalArgumentException e) {
			currentStep = WizardStep.WELCOME;
		}

		PersistFields persistFields = new PersistFields(request);

		//Skip the browser warning page if using Chrome in incognito mode
		if (currentStep == WizardStep.BROWSER_WARNING &&
				request.isChrome() && request.isIncognito()) {
			super.writeTemporaryRedirect(ctx, "Skipping unneeded warning",
			        persistFields.appendTo(TOADLET_URL+"?step=MISC"));
			return;
		} else if (currentStep == WizardStep.MISC && persistFields.isUsingPreset()) {
			/*If using a preset, skip the miscellaneous page as both high and low security set those settings.
			 * This overrides the persistence fields.*/
			StringBuilder redirectBase = new StringBuilder(TOADLET_URL+"?step=");
			if (persistFields.preset == WizardPreset.HIGH) {
				redirectBase.append("SECURITY_NETWORK&preset=HIGH&confirm=true&opennet=false&security-levels.networkThreatLevel=HIGH");
			} else /*if (persistFields.preset == WizardPreset.LOW)*/ {
				redirectBase.append("DATASTORE_SIZE&preset=LOW&opennet=true");
			}
			//addPersistFields() is not used here because the fields are overridden.
			super.writeTemporaryRedirect(ctx, "Skipping to next necessary step", redirectBase.toString());
			return;
		} else if (currentStep == WizardStep.SECURITY_NETWORK && !request.isParameterSet("opennet")) {
			//If opennet isn't defined when attempting to set network security level, ask again.
			super.writeTemporaryRedirect(ctx, "Need opennet choice",
			        persistFields.appendTo(TOADLET_URL+"?step=OPENNET"));
			return;
		} else if (currentStep == WizardStep.NAME_SELECTION && core.node.isOpennetEnabled()) {
			//Skip node name selection if not in darknet mode.
			super.writeTemporaryRedirect(ctx, "Skip name selection",
			        persistFields.appendTo(stepURL(WizardStep.DATASTORE_SIZE.name())));
			return;
		} else if (currentStep == WizardStep.COMPLETE) {
			super.writeTemporaryRedirect(ctx, "Wizard complete", WelcomeToadlet.PATH);
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

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
        if(!ctx.checkFullAccess(this))
            return;

		WizardStep currentStep;
		try {
			//Attempt to parse the current step, defaulting to WELCOME if unspecified or invalid.
			String currentValue = request.getPartAsStringFailsafe("step", 20);
			currentStep = currentValue.isEmpty() ? WizardStep.WELCOME : WizardStep.valueOf(currentValue);
		} catch (IllegalArgumentException e) {
			//Failed to parse enum value, default to welcome.
			//TODO: Should this be an error page instead?
			currentStep = WizardStep.WELCOME;
		}

		PersistFields persistFields = new PersistFields(request);
		String redirectTarget;

		if (currentStep.equals(WizardStep.WELCOME) &&
		        (request.isPartSet("presetLow") || request.isPartSet("presetHigh") || request.isPartSet("presetNone"))) {

			/*Apply presets and UPnP is enabled first to allow it time to load (and thus enable
			  autodetection) before hitting the bandwidth page. This also effectively sets the preset field.*/
			StringBuilder redirectTo = new StringBuilder(TOADLET_URL+"?step=BROWSER_WARNING&incognito=");
			redirectTo.append(request.getPartAsStringFailsafe("incognito", 5));

			//Translate button name to preset value on the query string.
			if (request.isPartSet("presetLow")) {
				//Low security preset
				stepMISC.setUPnP(true);
				stepMISC.setAutoUpdate(true);
				redirectTo.append("&preset=LOW&opennet=true");
				stepSECURITY_NETWORK.setThreatLevel(NetworkThreatLevel.LOW);
				stepSECURITY_PHYSICAL.setThreatLevel(PhysicalThreatLevel.NORMAL,
				        stepSECURITY_PHYSICAL.getCurrentLevel());
			} else if (request.isPartSet("presetHigh")) {
				//High security preset
				stepMISC.setUPnP(true);
				stepMISC.setAutoUpdate(true);
				redirectTo.append("&preset=HIGH&opennet=false");
			}

			super.writeTemporaryRedirect(ctx, "Wizard set preset", redirectTo.toString());
			return;
		} else if (request.isPartSet("back")) {
			//User chose back, return to previous page.
			redirectTarget = getPreviousStep(currentStep, persistFields.preset).name();
		} else {
			try {
				redirectTarget = steps.get(currentStep).postStep(request);

				//Opennet step can change the persisted value for opennet.
				if (currentStep == WizardStep.OPENNET) {
					try {
						HTTPRequest newRequest = new HTTPRequestImpl(new URI(
						        stepURL(redirectTarget)), "GET");
						//Only continue if a value for opennet has been selected.
						if (newRequest.isPartSet("opennet")) {
							redirectTarget = WizardStep.SECURITY_NETWORK.name();
							persistFields = new PersistFields(persistFields.preset, newRequest);
						}
					} catch (URISyntaxException e) {
						Logger.error(this, "Unexpected invalid query string from OPENNET step! "+e, e);
						redirectTarget = WizardStep.WELCOME.name();
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
		super.writeTemporaryRedirect(ctx, "Wizard redirect", stepURL(persistFields.appendTo(redirectTarget)));
	}

	private String stepURL(String step) {
		return TOADLET_URL+"?step="+step;
	}

	//FIXME: There really has to be a better way to find the previous step, but with an enum there's no decrement.
	//FIXME: Would a set work better than an enum?
	public static WizardStep getPreviousStep(WizardStep currentStep, WizardPreset preset) {

		//Might be obvious, but still: No breaks needed in cases because their only contents are returns.

		//First pages for the presets
		if (preset == WizardPreset.HIGH) {
			switch (currentStep) {
				case SECURITY_NETWORK:
				case SECURITY_PHYSICAL:
					//Go back to the beginning from the warning or the physical security page.
					return WizardStep.WELCOME;
				default:
					//do nothing
			}
		} else  if (preset == WizardPreset.LOW) {
			switch (currentStep) {
				case DATASTORE_SIZE:
					//Go back to the beginning from the datastore page.
					return WizardStep.WELCOME;
				default:
					//do nothing
			}
		}

		//Otherwise normal order.
		switch (currentStep) {
			case MISC:
			case BROWSER_WARNING:
				return WizardStep.WELCOME;
			case OPENNET:
				return WizardStep.MISC;
			case SECURITY_NETWORK:
				return WizardStep.OPENNET;
			case SECURITY_PHYSICAL:
				return WizardStep.SECURITY_NETWORK;
			case NAME_SELECTION:
				return WizardStep.SECURITY_PHYSICAL;
			case DATASTORE_SIZE:
				return WizardStep.NAME_SELECTION;
			case BANDWIDTH:
				return WizardStep.DATASTORE_SIZE;
			case BANDWIDTH_MONTHLY:
			case BANDWIDTH_RATE:
				return WizardStep.BANDWIDTH;
			default:
				//do nothing
		}

		//Should be matched by this point, unknown step.
		return WizardStep.WELCOME;
	}

	@Override
	public String path() {
		return TOADLET_URL;
	}
}
