/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class IPUndetectedUserAlert extends AbstractUserAlert {
	
	public IPUndetectedUserAlert(Node n) {
		super(true, null, null, null, null, (short) 0, true, NodeL10n.getBase().getString("UserAlert.hide"), false, null);
		this.node = n;
	}
	
	final Node node;
	
	@Override
	public String getTitle() {
		return l10n("unknownAddressTitle");
	}

	@Override
	public String getText() {
		if(node.ipDetector.noDetectPlugins())
			return l10n("noDetectorPlugins");
		if(node.ipDetector.isDetecting())
			return l10n("detecting");
		else
			return l10n("unknownAddress", "port", Integer.toString(node.getDarknetPortNumber())) + ' ' + textPortForwardSuggestion();
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("IPUndetectedUserAlert."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("IPUndetectedUserAlert."+key, pattern, value);
	}

	private String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("IPUndetectedUserAlert."+key, patterns, values);
	}
	
	@Override
	public boolean isValid() {
		if(node.isOpennetEnabled())
			return false;
		if(node.peers.countConnectiblePeers() >= 5 && (node.getUptime() < 60*1000 || node.ipDetector.isDetecting()))
			return false;
		return true;
	}

	@Override
	public HTMLNode getHTMLText() {
		HTMLNode textNode = new HTMLNode("div");
		SubConfig sc = node.config.get("node");
		Option<?> o = sc.getOption("tempIPAddressHint");
		
		NodeL10n.getBase().addL10nSubstitution(textNode, "IPUndetectedUserAlert."+(node.ipDetector.isDetecting() ? "detectingWithConfigLink" : "unknownAddressWithConfigLink"), 
				new String[] { "link" },
				new HTMLNode[] { HTMLNode.link("/config/"+sc.getPrefix()) });
		
		int peers = node.peers.getDarknetPeers().length;
		if(peers > 0)
			textNode.addChild("p", l10n("noIPMaybeFromPeers", "number", Integer.toString(peers)));
		
		if(node.ipDetector.noDetectPlugins()) {
			HTMLNode p = textNode.addChild("p");
			NodeL10n.getBase().addL10nSubstitution(p, "IPUndetectedUserAlert.loadDetectPlugins", new String[] { "plugins", "config", },
					new HTMLNode[] { HTMLNode.link("/plugins/"), HTMLNode.link("/config/node") });
		} else if(!node.ipDetector.hasJSTUN() && !node.ipDetector.isDetecting()) {
			HTMLNode p = textNode.addChild("p");
			NodeL10n.getBase().addL10nSubstitution(p, "IPUndetectedUserAlert.loadJSTUN", new String[] { "plugins" },
					new HTMLNode[] { HTMLNode.link("/plugins/") });
		}
		
		addPortForwardSuggestion(textNode);
		
		HTMLNode formNode = textNode.addChild("form", new String[] { "action", "method" }, new String[] { "/config/"+sc.getPrefix(), "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "subconfig", sc.getPrefix() });
		HTMLNode listNode = formNode.addChild("ul", "class", "config");
		HTMLNode itemNode = listNode.addChild("li");
		itemNode.addChild("span", "class", "configshortdesc", NodeL10n.getBase().getString(o.getShortDesc())).addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", sc.getPrefix() + ".tempIPAddressHint", o.getValueString() });
		itemNode.addChild("span", "class", "configlongdesc", NodeL10n.getBase().getString(o.getLongDesc()));
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", NodeL10n.getBase().getString("UserAlert.apply") });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", NodeL10n.getBase().getString("UserAlert.reset") });
		
		return textNode;
	}

	private void addPortForwardSuggestion(HTMLNode textNode) {
		// FIXME we should support any number of ports, UDP or TCP, and pick them up from the node as we do with the forwarding plugin ... that would be a bit of a pain for L10n though ...
		int darknetPort = node.getDarknetPortNumber();
		int opennetPort = node.getOpennetFNPPort();
		if(opennetPort <= 0) {
			textNode.addChild("#", " "+l10n("suggestForwardPort", "port", Integer.toString(darknetPort)));
		} else {
			textNode.addChild("#", " "+l10n("suggestForwardTwoPorts", new String[] { "port1", "port2" }, 
					new String[] { Integer.toString(darknetPort), Integer.toString(opennetPort) }));
		}
	}

	private String textPortForwardSuggestion() {
		// FIXME we should support any number of ports, UDP or TCP, and pick them up from the node as we do with the forwarding plugin ... that would be a bit of a pain for L10n though ...
		int darknetPort = node.getDarknetPortNumber();
		int opennetPort = node.getOpennetFNPPort();
		if(opennetPort <= 0) {
			return l10n("suggestForwardPort", "port", Integer.toString(darknetPort));
		} else {
			return " "+l10n("suggestForwardTwoPorts", new String[] { "port1", "port2" }, 
					new String[] { Integer.toString(darknetPort), Integer.toString(opennetPort) });
		}
	}

	@Override
	public short getPriorityClass() {
		if(node.ipDetector.isDetecting())
			return UserAlert.WARNING;
		else
			return UserAlert.ERROR;
	}

	@Override
	public String getShortText() {
		if(node.ipDetector.noDetectPlugins())
			return l10n("noDetectorPlugins");
		if(node.ipDetector.isDetecting())
			return l10n("detectingShort");
		else
			return l10n("unknownAddressShort");
	}

}
