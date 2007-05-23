/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class IPUndetectedUserAlert implements UserAlert {
	
	public IPUndetectedUserAlert(Node n) {
		this.node = n;
	}
	
	boolean isValid=true;
	final Node node;
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return l10n("unknownAddressTitle");
	}

	public String getText() {
		if(node.ipDetector.isDetecting())
			return l10n("detecting");
		else
			return l10n("unknownAddress", "port", Integer.toString(node.getPortNumber()));
	}

	private String l10n(String key) {
		return L10n.getString("IPUndetectedUserAlert."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("IPUndetectedUserAlert."+key, pattern, value);
	}

	public HTMLNode getHTMLText() {
		SubConfig sc = node.config.get("node");
		Option o = sc.getOption("tempIPAddressHint");
		
		HTMLNode textNode = new HTMLNode("div");
		L10n.addL10nSubstitution(textNode, "IPUndetectedUserAlert."+(node.ipDetector.isDetecting() ? "detectingWithConfigLink" : "unknownAddressWithConfigLink"), 
				new String[] { "link", "/link", "port" }, 
				new String[] { "<a href=\"/config/\">", "</a>", Integer.toString(node.getPortNumber()) });
		HTMLNode formNode = textNode.addChild("form", new String[] { "action", "method" }, new String[] { "/config/", "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		HTMLNode listNode = formNode.addChild("ul", "class", "config");
		HTMLNode itemNode = listNode.addChild("li");
		itemNode.addChild("span", "class", "configshortdesc", L10n.getString(o.getShortDesc())).addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", sc.getPrefix() + ".tempIPAddressHint", o.getValueString() });
		itemNode.addChild("span", "class", "configlongdesc", L10n.getString(o.getLongDesc()));
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", L10n.getString("UserAlert.apply") });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", L10n.getString("UserAlert.apply") });
		return textNode;
	}

	public short getPriorityClass() {
		if(node.ipDetector.isDetecting())
			return UserAlert.WARNING;
		else
			return UserAlert.ERROR;
	}
	
	public boolean isValid() {
		return isValid;
	}
	
	public void isValid(boolean validity){
		if(userCanDismiss()) isValid=validity;
	}
	
	public String dismissButtonText(){
		return L10n.getString("UserAlert.hide");
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
