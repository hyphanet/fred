package freenet.node.useralerts;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.JVMVersion;

/**
 * Informs the user that their current JVM is at EOL and Freenet will stop working with it in a future release.
 */
public class JVMVersionAlert extends AbstractUserAlert {

	public JVMVersionAlert() {
		super(false, null, null, null, null, UserAlert.ERROR, true, null, false, null);
	}

	@Override
	public String getTitle() {
		return NodeL10n.getBase().getString("JavaEOLAlert.title");
	}

	@Override
	public String getText() {
		return NodeL10n.getBase().getString("JavaEOLAlert.body",
		                                    new String[] {"current", "new"},
		                                    new String[] {JVMVersion.getCurrent(),
		                                                  JVMVersion.REQUIRED});
	}

	@Override
	public String getShortText() {
		return getTitle();
	}

	@Override
	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}
}
