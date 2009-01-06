package freenet.node.useralerts;

import freenet.clients.http.LinkFixer;
import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class ExtOldAgeUserAlert extends AbstractUserAlert {
	
	/**
	 * Creates a new alert.
	 */
	public ExtOldAgeUserAlert() {
		super(true, null, null, null, null, UserAlert.ERROR, true, L10n.getString("UserAlert.hide"), true, null);
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
		return L10n.getString("ExtOldAgeUserAlert."+key);
	}

	@Override
	public HTMLNode getHTMLText(LinkFixer fixer) {
		return new HTMLNode("div", getText());
	}

	@Override
	public String getShortText() {
		return l10n("extTooOldShort");
	}

}
