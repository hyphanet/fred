/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

/**
 * A simple user alert warning the user about the weird effect a time skew
 * can have on a freenet node.
 *
 * This useralert is SET only and can be triggered from NodeStarter
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class TimeSkewDetectedUserAlert implements UserAlert {
	private boolean isValid=false;
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return l10n("title");
	}
	
	private String l10n(String key) {
		return L10n.getString("TimeSkewDetectedUserAlert."+key);
	}

	public String getText() {
		return l10n("text");
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

	public short getPriorityClass() {
		return UserAlert.CRITICAL_ERROR;
	}

	public boolean isValid() {
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
		// can't happen!
	}
}
