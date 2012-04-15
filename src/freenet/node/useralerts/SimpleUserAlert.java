/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.support.HTMLNode;
import freenet.support.l10n.NodeL10n;

public class SimpleUserAlert extends AbstractUserAlert {

	public SimpleUserAlert(boolean canDismiss, String title, String text, String shortText, short type) {
		this(canDismiss, title, text, shortText, type, null);
	}
	
	public SimpleUserAlert(boolean canDismiss, String title, String text, String shortText, short type, Object userIdentifier) {
		super(canDismiss, title, text, shortText, new HTMLNode("div", text), type, true, NodeL10n.getBase().getString("UserAlert.hide"), true, userIdentifier);
	}

	@Override
	public void isValid(boolean validity) {
		// Do nothing
	}

}
