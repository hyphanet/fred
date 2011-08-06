package freenet.clients.http.wizardsteps;

import freenet.clients.http.PageNode;
import freenet.clients.http.ToadletContext;
import freenet.config.Config;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

import javax.naming.OperationNotSupportedException;

/**
 * An WizardL10n returns a rendered HTMLNode to return to the browser.
 */
public final class WizardL10n {

	/**
	 * Cannot be instantiated.
	 * @throws OperationNotSupportedException if called, because this class should be not be instantiated.
	 */
	private WizardL10n() throws OperationNotSupportedException {
		throw new OperationNotSupportedException("Cannot instantiate WizardL10n; it is a utility class.");
	}

	protected String l10n(String key) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key);
	}

	protected String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key, pattern, value);
	}

	protected String l10nSec(String key) {
		return NodeL10n.getBase().getString("SecurityLevels."+key);
	}
}
