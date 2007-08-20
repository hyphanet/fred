package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class OpennetUserAlert implements UserAlert {

	private final Node node;
	
	public OpennetUserAlert(Node node) {
		this.node = node;
	}
	
	public String dismissButtonText() {
		// Not dismissable
		return null;
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("#", getText());
	}

	public short getPriorityClass() {
		return UserAlert.WARNING;
	}

	public String getText() {
		return L10n.getString("OpennetUserAlert.warning");
	}

	public String getTitle() {
		return L10n.getString("OpennetUserAlert.warningTitle");
	}

	public boolean isValid() {
		return node.isOpennetEnabled();
	}

	public void isValid(boolean validity) {
		// Ignore
	}

	public void onDismiss() {
		// Can't dismiss
	}

	public boolean shouldUnregisterOnDismiss() {
		// Can't dismiss
		return false;
	}

	public boolean userCanDismiss() {
		// Can't dismiss
		return false;
	}

}
