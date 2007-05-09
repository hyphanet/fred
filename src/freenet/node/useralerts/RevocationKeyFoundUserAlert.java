/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class RevocationKeyFoundUserAlert implements UserAlert {
	private final String msg;
	
	public RevocationKeyFoundUserAlert(String msg){
		this.msg=msg;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return L10n.getString("RevocationKeyFoundUserAlert.title");
	}

	public String getText() {
		//TODO: reformulate : maybe put the GPG key fingerprint of "trusted devs"
		return L10n.getString("RevocationKeyFoundUserAlert.text", "message", msg);
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

	public short getPriorityClass() {
		return UserAlert.CRITICAL_ERROR;
	}
	
	public boolean isValid() {
		return true;
	}
	
	public void isValid(boolean b){
		// We ignore it : it's ALWAYS valid !
	}
	
	public String dismissButtonText(){
		return null; // can't be dismissed
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
