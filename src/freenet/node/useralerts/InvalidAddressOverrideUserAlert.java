/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class InvalidAddressOverrideUserAlert extends AbstractUserAlert {
	
	public InvalidAddressOverrideUserAlert(Node n) {
		super(false, null, null, null, null, (short) 0, true, null, false, null);
		this.node = n;
	}
	
	final Node node;
	
	@Override
	public String getTitle() {
		return l10n("unknownAddressTitle");
	}

	@Override
	public String getText() {
		return l10n("unknownAddress");
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("InvalidAddressOverrideUserAlert."+key);
	}

	@Override
	public HTMLNode getHTMLText() {
		SubConfig sc = node.config.get("node");
		Option<?> o = sc.getOption("ipAddressOverride");
		
		HTMLNode textNode = new HTMLNode("div");
		NodeL10n.getBase().addL10nSubstitution(textNode, "InvalidAddressOverrideUserAlert.unknownAddressWithConfigLink", 
				new String[] { "link" }, 
				new HTMLNode[] { HTMLNode.link("/config/node")});
		HTMLNode formNode = textNode.addChild("form", new String[] { "action", "method" }, new String[] { "/config/node", "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "subconfig", sc.getPrefix() });
		HTMLNode listNode = formNode.addChild("ul", "class", "config");
		HTMLNode itemNode = listNode.addChild("li");
		itemNode.addChild("span", "class", "configshortdesc", NodeL10n.getBase().getString(o.getShortDesc())).addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", sc.getPrefix() + ".ipAddressOverride", o.getValueString() });
		itemNode.addChild("span", "class", "configlongdesc", NodeL10n.getBase().getString(o.getLongDesc()));
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", NodeL10n.getBase().getString("UserAlert.apply") });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", NodeL10n.getBase().getString("UserAlert.reset") });
		return textNode;
	}

	@Override
	public short getPriorityClass() {
		return UserAlert.ERROR;
	}

	@Override
	public String getShortText() {
		return l10n("unknownAddressShort");
	}

}
