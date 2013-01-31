/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;

public class RevocationKeyFoundUserAlert extends AbstractUserAlert {
	public RevocationKeyFoundUserAlert(String msg, boolean disabledNotBlown){
		super(false,
				getTitle(disabledNotBlown),
				getText(disabledNotBlown, msg),
				getText(disabledNotBlown, msg),
				getHTML(disabledNotBlown, msg), 
				UserAlert.CRITICAL_ERROR, true, null, false, null);
	}
	
	private static HTMLNode getHTML(boolean disabledNotBlown, String msg) {
		HTMLNode div = new HTMLNode("div");
		if(disabledNotBlown) {
			div.addChild("p", NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.textDisabled"));
			div.addChild("p", NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.textDisabledDetail", "message", msg));
		} else {
			div.addChild("p", NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.text"));
			div.addChild("p", NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.textDetail", "message", msg));
		}
		return div;
	}

	private static String getText(boolean disabledNotBlown, String msg) {
		if(disabledNotBlown) {
			return NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.textDisabled") + " " +
				NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.textDisabledDetail", "message", msg);
		} else {
			return NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.text") + " " +
				NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.textDetail", "message", msg);
		}
	}

	private static String getTitle(boolean disabledNotBlown) {
		if(disabledNotBlown)
			return NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.titleDisabled");
		else
			return NodeL10n.getBase().getString("RevocationKeyFoundUserAlert.title");
	}

	@Override
	public void isValid(boolean b){
		// We ignore it : it's ALWAYS valid !
	}
	
}
