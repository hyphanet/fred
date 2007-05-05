/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class BuildOldAgeUserAlert implements UserAlert {
	private boolean isValid=true;
	public int lastGoodVersion = 0;
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return l10n("tooOldTitle");
	}
	
	private String l10n(String key) {
		return L10n.getString("BuildOldAgeUserAlert."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("BuildOldAgeUserAlert."+key, pattern, value);
	}

	public String getText() {
	  if(lastGoodVersion == 0)
		  throw new IllegalArgumentException("Not valid");
		String s = l10n("tooOld", "lastgood", Integer.toString(lastGoodVersion));
		return s;
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

	public short getPriorityClass() {
		return UserAlert.ERROR;
	}

	public boolean isValid() {
	  if(lastGoodVersion == 0)
	    return false;
		return isValid;
	}
	
	public void isValid(boolean b){
		if(userCanDismiss()) isValid=b;
	}
	
	public String dismissButtonText(){
		return L10n.getString("UserAlert.hide");
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
