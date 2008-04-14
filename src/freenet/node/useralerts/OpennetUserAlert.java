package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class OpennetUserAlert extends AbstractUserAlert {

	private final Node node;
	
	public OpennetUserAlert(Node node) {
		super(true, L10n.getString("OpennetUserAlert.warningTitle"), L10n.getString("OpennetUserAlert.warning"), new HTMLNode("#", L10n.getString("OpennetUserAlert.warning")), UserAlert.WARNING, false, L10n.getString("UserAlert.hide"), false, null);
		this.node = node;
	}
	
	public boolean isValid() {
		return node.isOpennetEnabled();
	}

}
