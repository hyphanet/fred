package freenet.clients.http.wizardsteps;

import freenet.clients.http.PageNode;
import freenet.clients.http.ToadletContext;
import freenet.config.Config;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

import javax.naming.OperationNotSupportedException;

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

	public static String l10nSec(String key) {
		return NodeL10n.getBase().getString("SecurityLevels."+key);
	}
}
