package freenet.node.useralerts;

import freenet.support.HTMLNode;
import freenet.support.l10n.NodeL10n;

public class ExtOldAgeUserAlert extends AbstractUserAlert {
	
	/**
	 * Creates a new alert.
	 */
	public ExtOldAgeUserAlert() {
		super(true, null, null, null, null, UserAlert.ERROR, true, NodeL10n.getBase().getString("UserAlert.hide"), true, null);
	}
	
	@Override
	public String getTitle() {
		return l10n("extTooOldTitle");
	}
	
	@Override
	public String getText() {
		return l10n("extTooOld");
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("ExtOldAgeUserAlert."+key);
	}

	@Override
	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

	@Override
	public String getShortText() {
		return l10n("extTooOldShort");
	}

}
