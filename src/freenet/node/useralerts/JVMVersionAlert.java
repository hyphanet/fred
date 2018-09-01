package freenet.node.useralerts;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.JVMVersion;

/**
 * Informs the user that their current JVM is at EOL and Freenet will stop working with it in a future release.
 */
public class JVMVersionAlert extends AbstractUserAlert {

	public JVMVersionAlert() {
        super(true, null, null, null, null, UserAlert.WARNING, true,
              NodeL10n.getBase().getString("UserAlert.hide"), true, null);
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
		                                                  JVMVersion.EOL_THRESHOLD});
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
