package freenet.node.useralerts;

import freenet.node.PeerNode;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;

// Node To Node Text Message User Alert
public class N2NTMUserAlert implements UserAlert {
	private boolean isValid=true;
	private PeerNode sourcePeerNode;
	private String sourceNodename;
	private String targetNodename;
	private String messageText;

	public N2NTMUserAlert(PeerNode sourcePeerNode, String source, String target, String message) {
	  this.sourcePeerNode = sourcePeerNode;
		this.sourceNodename = source;
		this.targetNodename = target;
		this.messageText = message;
		isValid=true;
	}
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return "Node To Node Text Message";
	}
	
	public String getText() {
	  String messageTextBuf = HTMLEncoder.encode(messageText);
	  int j = messageTextBuf.length();
		StringBuffer messageTextBuf2 = new StringBuffer(j);
		for (int i = 0; i < j; i++) {
		  char ch = messageTextBuf.charAt(i);
		  if(ch == '\n')
		    messageTextBuf2.append("<br />");
		  else
		    messageTextBuf2.append(ch);
		}
    String replyString = "<a href=\"/send_n2ntm/?peernode_hashcode="+sourcePeerNode.hashCode()+"\">Reply</a><br /><br />";
		String s;
		s = "From: &lt;"+HTMLEncoder.encode(sourceNodename)+"&gt;<br />To: &lt;"+HTMLEncoder.encode(targetNodename)+"&gt;<hr /><br /><br />"+messageTextBuf2+"<br /><br />"+replyString;
		return s;
	}

	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("p", "From: " + sourceNodename);
		String[] lines = messageText.split("\n");
		for (int i = 0, c = lines.length; i < c; i++) {
			alertNode.addChild("div", lines[i]);
		}
		alertNode.addChild("p").addChild("a", "href", "/send_n2ntm/?peernode_hashcode=" + sourcePeerNode.hashCode(), "Reply");
		return alertNode;
	}

	public short getPriorityClass() {
		return UserAlert.MINOR;
	}

	public boolean isValid() {
		return isValid;
	}
	
	public void isValid(boolean b){
		if(userCanDismiss()) isValid=b;
	}
	
	public String dismissButtonText(){
		return "Delete";
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return true;
	}
}
