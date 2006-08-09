package freenet.node.useralerts;

import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.support.HTMLEncoder;
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
		SubConfig sc = node.config.get("node");
		Option o = sc.getOption("name");
		StringBuffer buf = new StringBuffer();
		
		buf.append("It seems that your node's name isn't defined. Setting " +
		"up a node name doesn't affect your anonymity in any way but " +
		"is useful for your peers to know who you are in case they have " +
		"to reach you. You can change the node's name at the Configuration page. " +
		"Putting your e-mail address or IRC nickname there is generally speaking " +
		"a good idea and helps your friends to identify your node.");
		buf.append("<form method=\"post\" action=\"/config/\">");
		buf.append("<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">");
		//buf.append("<ul class=\"config\"><span class=\"configprefix\">"+sc.getPrefix()+"</span>\n");
		buf.append("<ul class=\"config\">\n");
		buf.append("<li>");
		buf.append("<span class=\"configshortdesc\">");
		buf.append(o.getShortDesc());
		buf.append("</span>");	
		buf.append("<span class=\"config\">");
		buf.append("<input alt=\""+o.getShortDesc()+"\" class=\"config\"" +
				" type=\"text\" name=\""+sc.getPrefix()+".name\" value=\""+HTMLEncoder.encode(o.getValueString())+"\" />");				
		buf.append("</span>");
		buf.append("<span class=\"configlongdesc\">");
		buf.append(o.getLongDesc());
		buf.append("</span>");
		buf.append("</li>\n");
		buf.append("</ul>\n");
		buf.append("<input type=\"submit\" value=\"Apply\" />");
		buf.append("<input type=\"reset\" value=\"Reset\" />");
		buf.append("</form>");
		
		return buf.toString();
	}

	public HTMLNode getHTMLText() {
		SubConfig sc = node.config.get("node");
		Option o = sc.getOption("name");

		HTMLNode alertNode = new HTMLNode("div");
		HTMLNode textNode = alertNode.addChild("div", "It seems that your node\u2019s name is not defined. Setting up a node name does not affect your anonymity in any way but is useful for your peers to know who you are in case they have to reach you. You can change the node\u2019s name at the ");
		textNode.addChild("a", "href", "/config/", "Configuration Page");
		textNode.addChild("#", ". Putting your e-mail address or IRC nickname there is generally speaking a good idea and helps your friends to identify your node.");
		HTMLNode formNode = alertNode.addChild("form", new String[] { "action", "method" }, new String[] { "/config/", "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.formPassword });
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
}
