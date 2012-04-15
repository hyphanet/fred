/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.support.HTMLNode;
import freenet.support.l10n.NodeL10n;

public class BuildOldAgeUserAlert extends AbstractUserAlert {
	public int lastGoodVersion = 0;
	
	public BuildOldAgeUserAlert() {
		super(false, null, null, null, null, UserAlert.ERROR, true, NodeL10n.getBase().getString("UserAlert.hide"), false, null);
	}
	
	@Override
	public String getTitle() {
		return l10n("tooOldTitle");
	}
	
	private String l10n(String key) {
		return NodeL10n.getBase().getString("BuildOldAgeUserAlert."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("BuildOldAgeUserAlert."+key, pattern, value);
	}

	@Override
	public String getText() {
	  if(lastGoodVersion == 0)
		  throw new IllegalArgumentException("Not valid");
		String s = l10n("tooOld", "lastgood", Integer.toString(lastGoodVersion));
		return s;
	}

	@Override
	public HTMLNode getHTMLText() {
		return new HTMLNode("div", getText());
	}

	@Override
	public boolean isValid() {
		if (lastGoodVersion == 0)
			return false;
		return super.isValid();
	}
	
	@Override
	public String getShortText() {
		return l10n("tooOldShort", "lastgood", Integer.toString(lastGoodVersion));
	}

}
