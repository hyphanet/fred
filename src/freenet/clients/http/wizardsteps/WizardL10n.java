package freenet.clients.http.wizardsteps;

import javax.naming.OperationNotSupportedException;

import freenet.l10n.NodeL10n;

/**
 * A static utility class for performing l10n from wizard steps.
 */
public final class WizardL10n {

	/**
	 * Cannot be instantiated.
	 * @throws OperationNotSupportedException if called, because this class should be not be instantiated.
	 */
	private WizardL10n() throws OperationNotSupportedException {
		throw new OperationNotSupportedException("Cannot instantiate WizardL10n; it is a utility class.");
	}

	public static String l10n(String key) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key);
	}

	public static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key, pattern, value);
	}

	public static String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key, patterns, values);
	}

	public static String l10nSec(String key) {
		return NodeL10n.getBase().getString("SecurityLevels."+key);
	}
}
