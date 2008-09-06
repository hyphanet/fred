/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class InvalidAddressOverrideUserAlert extends AbstractUserAlert {
	
	public InvalidAddressOverrideUserAlert(Node n) {
		super(false, null, null, null, null, (short) 0, true, null, false, null);
		this.node = n;
	}
	
	final Node node;
	
	public String getTitle() {
		return l10n("unknownAddressTitle");
	}

	public String getText() {
		return l10n("unknownAddress");
	}

	private String l10n(String key) {
		return L10n.getString("InvalidAddressOverrideUserAlert."+key);
	}

	public HTMLNode getHTMLText() {
		SubConfig sc = node.config.get("node");
		Option<?> o = sc.getOption("ipAddressOverride");
		
		HTMLNode textNode = new HTMLNode("div");
		L10n.addL10nSubstitution(textNode, "InvalidAddressOverrideUserAlert.unknownAddressWithConfigLink", 
				new String[] { "link", "/link" }, 
				new String[] { "<a href=\"/config/\">", "</a>" });
		HTMLNode formNode = textNode.addChild("form", new String[] { "action", "method" }, new String[] { "/config/", "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		HTMLNode listNode = formNode.addChild("ul", "class", "config");
		HTMLNode itemNode = listNode.addChild("li");
		itemNode.addChild("span", "class", "configshortdesc", L10n.getString(o.getShortDesc())).addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", sc.getPrefix() + ".ipAddressOverride", o.getValueString() });
		itemNode.addChild("span", "class", "configlongdesc", L10n.getString(o.getLongDesc()));
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", L10n.getString("UserAlert.apply") });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", L10n.getString("UserAlert.reset") });
		return textNode;
	}

	public short getPriorityClass() {
		return UserAlert.ERROR;
	}

	public String getShortText() {
		return l10n("unknownAddressShort");
	}

}
