package freenet.node.useralerts;

import freenet.node.NodeIPDetector;
import freenet.support.HTMLNode;

public class IPUndetectedUserAlert implements UserAlert {
	
	public IPUndetectedUserAlert(NodeIPDetector ipm) {
		this.ipm = ipm;
	}
	
	boolean isValid=true;
	final NodeIPDetector ipm;
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return "Unknown external address";
	}

	public String getText() {
		if(ipm.isDetecting())
			return "Freenet is currently attempting to detect your external IP address. " +
					"If this takes more than a few minutes there is something wrong...";
		else
			return "Freenet was unable to determine your external IP address " +
				"(or the IP address of your NAT or Firewall). You can still exchange " +
				"references with other people, however this will only work if the other " +
				"user is not behind a NAT or Firewall. As soon as you have connected to " +
				"one other user in this way, Freenet will be able to determine your " +
				"external IP address. You can determine your current IP address and tell " +
				"your node with the 'Temporary IP address hint' <a href=\"/config/\">configuration parameter</a>.";
	}

	public HTMLNode getHTMLText() {
		HTMLNode textNode = new HTMLNode("div");
		if(ipm.isDetecting())
			textNode.addChild("#", "Freenet is currently attempting to detect your external IP address. If this takes more than a few minutes there is something wrong and you can use the Temporary IP Address Hint ");
		else
			textNode.addChild("#", "Freenet was unable to determine your external IP address (or the IP address of your NAT-device or firewall). You can still exchange references with other people, however this will only work if the other user is not behind a NAT-device or firewall. As soon as you have connected to one other user in this way, Freenet will be able to determine your external IP address. You can determine your current IP address and tell your node with the \u201cTemporary IP Address Hint\u201d ");
		textNode.addChild("a", "href", "/config/", "configuration parameter");
		textNode.addChild("#", ".");
		return textNode;
	}

	public short getPriorityClass() {
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
