/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.clients.http.LinkFixer;
import freenet.l10n.L10n;
import freenet.support.HTMLNode;

/**
 * A useralert to tell the user to update his computer's clock
 *
 * This useralert is SET only and can be triggered from NodeStarter
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class ClockProblemDetectedUserAlert extends AbstractUserAlert {
	
	/**
	 * 
	 */
	public ClockProblemDetectedUserAlert() {
		super(false, null, null, null, null, UserAlert.CRITICAL_ERROR, true, null, false, null);
	}

	@Override
	public String getTitle() {
		return l10n("title");
	}
	
	private String l10n(String key) {
		return L10n.getString("ClockProblemDetectedUserAlert."+key);
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
	public HTMLNode getHTMLText(LinkFixer fixer) {
		return new HTMLNode("div", getText());
	}

	@Override
	public boolean userCanDismiss() {
		return false;
	}
}
