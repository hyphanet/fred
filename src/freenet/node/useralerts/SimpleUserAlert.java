/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;

public class SimpleUserAlert extends AbstractUserAlert {

	public SimpleUserAlert(boolean canDismiss, String title, String text, short type) {
		this(canDismiss, title, text, type, null);
	}
	
	public SimpleUserAlert(boolean canDismiss, String title, String text, short type, Object userIdentifier) {
		super(canDismiss, title, text, new HTMLNode("div", text), type, true, L10n.getString("UserAlert.hide"), true, userIdentifier);
	}

	public void isValid(boolean validity) {
		// Do nothing
	}

}
