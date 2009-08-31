package freenet.node.useralerts;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;

public class SimpleHTMLUserAlert extends AbstractUserAlert {

	public SimpleHTMLUserAlert(boolean canDismiss, String title, String shortText, HTMLNode content, short type) {
		super(canDismiss, title, content.getContent(), shortText, content, type, true, NodeL10n.getBase().getString("UserAlert.hide"), true, null);
	}

	@Override
	public void isValid(boolean validity) {
		// Do nothing
	}

}
