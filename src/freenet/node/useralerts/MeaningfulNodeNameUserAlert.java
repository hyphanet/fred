/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class MeaningfulNodeNameUserAlert extends AbstractUserAlert {
	private final Node node;

	public MeaningfulNodeNameUserAlert(Node n) {
		super(true, null, null, null, null, UserAlert.WARNING, true, NodeL10n.getBase().getString("UserAlert.hide"), true, null);
		this.node = n;
	}
	
	@Override
	public String getTitle() {
		return l10n("noNodeNickTitle");
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("MeaningfulNodeNameUserAlert."+key);
	}

	@Override
	public String getText() {
		return l10n("noNodeNick");
	}
	
	@Override
	public String getShortText() {
		return l10n("noNodeNickShort");
	}

	@Override
	public HTMLNode getHTMLText() {
		SubConfig sc = node.config.get("node");
		Option<?> o = sc.getOption("name");

		HTMLNode alertNode = new HTMLNode("div");
		HTMLNode textNode = alertNode.addChild("div");
		textNode.addChild("#", l10n("noNodeNick"));
		HTMLNode formNode = alertNode.addChild("form", new String[] { "action", "method" }, new String[] { "/config/"+sc.getPrefix(), "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "subconfig", sc.getPrefix() });
		HTMLNode listNode = formNode.addChild("ul", "class", "config");
		HTMLNode itemNode = listNode.addChild("li");
		itemNode.addChild("span", new String[]{ "class", "title", "style" },
				new String[]{ "configshortdesc", NodeL10n.getBase().getString("ConfigToadlet.defaultIs", new String[] { "default" }, new String[] { o.getDefault() }), 
				"cursor: help;" }).addChild(NodeL10n.getBase().getHTMLNode(o.getShortDesc()));
		itemNode.addChild("input", new String[] { "type", "class", "alt", "name", "value" }, new String[] { "text", "config", o.getShortDesc(), "node.name", o.getValueString() });
		itemNode.addChild("span", "class", "configlongdesc").addChild(NodeL10n.getBase().getHTMLNode(o.getLongDesc()));
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", NodeL10n.getBase().getString("UserAlert.apply") });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", NodeL10n.getBase().getString("UserAlert.reset") });

		return alertNode;
	}

	@Override
	public boolean isValid() {
		return node.peers.anyDarknetPeers();
	}
}
