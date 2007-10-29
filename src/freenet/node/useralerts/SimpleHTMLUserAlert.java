package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class SimpleHTMLUserAlert extends AbstractUserAlert {

	public SimpleHTMLUserAlert(boolean canDismiss, String title, HTMLNode content, short type) {
		super(canDismiss, title, content.getContent(), content, type, true, L10n.getString("UserAlert.hide"), true, null);
	}

	public void isValid(boolean validity) {
		// Do nothing
	}

}
