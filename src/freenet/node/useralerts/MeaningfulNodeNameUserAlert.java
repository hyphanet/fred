/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.support.HTMLNode;

public class MeaningfulNodeNameUserAlert implements UserAlert {
	private boolean isValid=true;
	private final Node node;

	public MeaningfulNodeNameUserAlert(Node n) {
		this.node = n;
	}
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return "Your node name isn't defined";
	}

	public String getText() {
		StringBuffer buf = new StringBuffer();
		
		buf.append("It seems that your node doesn't know your nickname." +
		"Putting your e-mail address or IRC nickname here is generally speaking " +
		"a good idea and helps your friends to identify your node.");
		
		return buf.toString();
	}

	public HTMLNode getHTMLText() {
		SubConfig sc = node.config.get("node");
		Option o = sc.getOption("name");

		HTMLNode alertNode = new HTMLNode("div");
		HTMLNode textNode = alertNode.addChild("div", "It seems that your node doesn\u2019t know your nickname.");
		textNode.addChild("a", "href", "/config/", "Configuration Page");
		textNode.addChild("#", ". Putting your e-mail address or IRC nickname there is generally speaking a good idea and helps your friends to identify your node.");
		HTMLNode formNode = alertNode.addChild("form", new String[] { "action", "method" }, new String[] { "/config/", "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
		HTMLNode listNode = formNode.addChild("ul", "class", "config");
		HTMLNode itemNode = listNode.addChild("li");
		itemNode.addChild("span", "class", "configshortdesc", o.getShortDesc()).addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", sc.getPrefix() + ".name", o.getValueString() });
		itemNode.addChild("span", "class", "configlongdesc", o.getLongDesc());
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Apply" });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", "Reset" });

		return alertNode;
	}

	public short getPriorityClass() {
		return UserAlert.WARNING;
	}
	
	public boolean isValid() {
		return isValid;
	}
	
	public void isValid(boolean b){
		if(userCanDismiss()) isValid=b;
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
