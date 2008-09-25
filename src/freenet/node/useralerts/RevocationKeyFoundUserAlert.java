/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class RevocationKeyFoundUserAlert extends AbstractUserAlert {
	public RevocationKeyFoundUserAlert(String msg){
		super(false, L10n.getString("RevocationKeyFoundUserAlert.title"), L10n.getString("RevocationKeyFoundUserAlert.text", "message", msg), L10n.getString("RevocationKeyFoundUserAlert.text", "message", msg), new HTMLNode("#", L10n.getString("RevocationKeyFoundUserAlert.text", "message", msg)), UserAlert.CRITICAL_ERROR, true, null, false, null);
	}
	
	@Override
	public void isValid(boolean b){
		// We ignore it : it's ALWAYS valid !
	}
	
}
