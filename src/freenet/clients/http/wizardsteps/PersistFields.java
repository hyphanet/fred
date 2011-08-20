package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.support.Fields;
import freenet.support.api.HTTPRequest;

/**
 * Parses an HTTPRequest and makes fields that should be persisted available through public properties.
 */
public class PersistFields {

	public final FirstTimeWizardToadlet.WIZARD_PRESET preset;
	public final boolean opennet;

	public PersistFields(HTTPRequest request) {

		String presetRaw;
		String opennetRaw;

		if (request.hasParameters()) {
			presetRaw = request.getParam("preset");
			opennetRaw = request.getParam("opennet", "false");
		} else {
			presetRaw = request.getPartAsStringFailsafe("preset", 4);
			opennetRaw = request.getPartAsStringFailsafe("opennet", 5);
		}

		//Assigning to preset directly counts as multiple modification.
		FirstTimeWizardToadlet.WIZARD_PRESET temp;
		try {
			temp = FirstTimeWizardToadlet.WIZARD_PRESET.valueOf(presetRaw);
		} catch (IllegalArgumentException e) {
			temp = null;
		}
		this.preset = temp;

		this.opennet = Fields.stringToBool(opennetRaw, false);
	}

	public boolean isUsingPreset() {
		return preset != null;
	}
}
