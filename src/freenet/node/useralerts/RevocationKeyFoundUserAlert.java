/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class RevocationKeyFoundUserAlert extends AbstractUserAlert {
	public RevocationKeyFoundUserAlert(String msg, boolean disabledNotBlown){
		super(false,
				getTitle(disabledNotBlown),
				getText(disabledNotBlown, msg),
				getText(disabledNotBlown, msg), 
				new HTMLNode("#", getText(disabledNotBlown, msg)), 
				UserAlert.CRITICAL_ERROR, true, null, false, null);
	}
	
	private static String getText(boolean disabledNotBlown, String msg) {
		if(disabledNotBlown)
			return L10n.getString("RevocationKeyFoundUserAlert.textDisabled", "message", msg);
		else
			return L10n.getString("RevocationKeyFoundUserAlert.text", "message", msg);
	}

	private static String getTitle(boolean disabledNotBlown) {
		if(disabledNotBlown)
			return L10n.getString("RevocationKeyFoundUserAlert.titleDisabled");
		else
			return L10n.getString("RevocationKeyFoundUserAlert.title");
	}

	@Override
	public void isValid(boolean b){
		// We ignore it : it's ALWAYS valid !
	}
	
}
