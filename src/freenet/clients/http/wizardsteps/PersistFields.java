package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.support.Fields;
import freenet.support.api.HTTPRequest;

/**
 * Handles fields that should be persisted, making them available through public properties.
 * Able to parse these fields from an HTTPRequest.
 */
public class PersistFields {

	public final FirstTimeWizardToadlet.WIZARD_PRESET preset;
	public final boolean opennet;
	public final boolean singleStep;

	/**
	 * @param request Parsed for persistence fields, checking parameters (GET) first, then parts (POST).
	 */
	public PersistFields(HTTPRequest request) {
		this.preset = parsePreset(request);
		this.opennet = parseOpennet(request);
		this.singleStep = parseSingleStep(request);
	}

	/**
	 * @param opennet Set manually
	 * @param request Parsed for remaining fields. (preset)
	 */
	public PersistFields(boolean opennet, HTTPRequest request) {
		this.preset = parsePreset(request);
		this.opennet = opennet;
		this.singleStep = parseSingleStep(request);
	}

	/**
	 * @param preset Set manually
	 * @param request Parsed for remaining fields. (opennet)
	 */
	public PersistFields(FirstTimeWizardToadlet.WIZARD_PRESET preset, HTTPRequest request) {
		this.preset = preset;
		this.opennet = parseOpennet(request);
		this.singleStep = parseSingleStep(request);
	}

	private FirstTimeWizardToadlet.WIZARD_PRESET parsePreset(HTTPRequest request) {
		String presetRaw;
		FirstTimeWizardToadlet.WIZARD_PRESET preset;

		if (request.hasParameters()) {
			presetRaw = request.getParam("preset");
		} else {
			presetRaw = request.getPartAsStringFailsafe("preset", 4);
		}

		try {
			preset = FirstTimeWizardToadlet.WIZARD_PRESET.valueOf(presetRaw);
		} catch (IllegalArgumentException e) {
			preset = null;
		}

		return preset;
	}

	private boolean parseOpennet(HTTPRequest request) {
		String opennetRaw;

		if (request.hasParameters()) {
			opennetRaw = request.getParam("opennet", "false");
		} else {
			opennetRaw = request.getPartAsStringFailsafe("opennet", 5);
		}

		return Fields.stringToBool(opennetRaw, false);
	}

	private boolean parseSingleStep(HTTPRequest request) {
		String singleStepRaw;

		if (request.hasParameters()) {
			singleStepRaw = request.getParam("singlestep", "false");
		} else {
			singleStepRaw = request.getPartAsStringFailsafe("singlestep", 5);
		}

		return Fields.stringToBool(singleStepRaw, false);
	}

	public boolean isUsingPreset() {
		return preset != null;
	}

	public boolean isSingleStep() {
		return singleStep;
	}

	/**
	 * Appends any defined persistence fields to the given URL.
	 * @param baseURL The URL to append fields to.
	 * @return URL with persistence fields included.
	 */
	public String appendTo(String baseURL) {
		StringBuilder url = new StringBuilder(baseURL).append("&opennet=").append(opennet);
		if (isUsingPreset()) {
			url.append("&preset=").append(preset);
		}
		if (isSingleStep()) {
			url.append("&singlestep=").append(singleStep);
		}
		return url.toString();
	}
}
