package freenet.node.useralerts;

import freenet.support.HTMLEncoder;

// Node To Node Text Message User Alert
public class N2NTMUserAlert implements UserAlert {
	private boolean isValid=true;
	public int lastGoodVersion = 0;
	private String source_nodename;
	private String target_nodename;
	private String message_text;

	public N2NTMUserAlert(String source, String target, String message) {
		this.source_nodename = source;
		this.target_nodename = target;
		this.message_text = message;
		isValid=true;
	}
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return "Node To Node Text Message";
	}
	
	public String getText() {
		String s;
		s = "You, as nodename '"+HTMLEncoder.encode(target_nodename)+"', have received a node to node text message from nodename '"+HTMLEncoder.encode(source_nodename)+"':<br /><br />"+HTMLEncoder.encode(message_text);
		return s;
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
}
