/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.config.Option;
import freenet.config.SubConfig;
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
		return "Unknown external address";
	}

	public String getText() {
		if(node.ipDetector.isDetecting())
			return "Freenet is currently attempting to detect your external IP address. " +
					"If this takes more than a few minutes there is something wrong...";
		else
			return "Freenet was unable to determine your external IP address " +
				"(or the IP address of your NAT or Firewall). You can still exchange " +
				"references with other people, however this will only work if the other " +
				"user is not behind a NAT or Firewall. As soon as you have connected to " +
				"one other user in this way, Freenet will be able to determine your " +
				"external IP address. You can determine your current IP address and tell " +
				"your node with the 'Temporary IP address hint' configuration parameter.";
	}

	public HTMLNode getHTMLText() {
		SubConfig sc = node.config.get("node");
		Option o = sc.getOption("tempIPAddressHint");
		
		HTMLNode textNode = new HTMLNode("div");
		if(node.ipDetector.isDetecting())
			textNode.addChild("#", "Freenet is currently attempting to detect your external IP address. If this takes more than a few minutes there is something wrong and you can use the Temporary IP Address Hint ");
		else
			textNode.addChild("#", "Freenet was unable to determine your external IP address (or the IP address of your NAT-device or firewall). You can still exchange references with other people, however this will only work if the other user is not behind a NAT-device or firewall. As soon as you have connected to one other user in this way, Freenet will be able to determine your external IP address. You can determine your current IP address and tell your node with the \u201cTemporary IP Address Hint\u201d ");
		textNode.addChild("a", "href", "/config/", "configuration parameter");
		textNode.addChild("#", ".");
		HTMLNode formNode = textNode.addChild("form", new String[] { "action", "method" }, new String[] { "/config/", "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		HTMLNode listNode = formNode.addChild("ul", "class", "config");
		HTMLNode itemNode = listNode.addChild("li");
		itemNode.addChild("span", "class", "configshortdesc", o.getShortDesc()).addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", sc.getPrefix() + ".tempIPAddressHint", o.getValueString() });
		itemNode.addChild("span", "class", "configlongdesc", o.getLongDesc());
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Apply" });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", "Reset" });
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
		return "Hide";
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
