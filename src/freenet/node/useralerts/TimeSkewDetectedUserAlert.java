/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;

/**
 * A simple user alert warning the user about the weird effect a time skew
 * can have on a freenet node.
 *
 * This useralert is SET only and can be triggered from NodeStarter
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class TimeSkewDetectedUserAlert extends AbstractUserAlert {
	
	/**
	 * 
	 */
	public TimeSkewDetectedUserAlert() {
		super(false, null, null, null, null, UserAlert.CRITICAL_ERROR, false, NodeL10n.getBase().getString("UserAlert.hide"), false, null);
	}
	
	@Override
	public String getTitle() {
		return l10n("title");
	}
	
	private String l10n(String key) {
		return NodeL10n.getBase().getString("TimeSkewDetectedUserAlert."+key);
	}

	@Override
	public String getText() {
		return l10n("text");
	}
	
	@Override
	public String getShortText() {
		return l10n("shortText");
	}

	@Override
	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

}
