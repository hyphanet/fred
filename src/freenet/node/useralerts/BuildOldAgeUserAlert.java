package freenet.node.useralerts;

import freenet.support.HTMLNode;

public class BuildOldAgeUserAlert implements UserAlert {
	private boolean isValid=true;
	public int lastGoodVersion = 0;
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return "Build too old";
	}
	
	public String getText() {
	  if(lastGoodVersion == 0)
		  throw new IllegalArgumentException("Not valid");
		String s;
		s = "This node's software is older than the oldest version (Build #"+lastGoodVersion+") allowed by the newest peers we " +
				"try to connect to.  Please update your node as soon as possible as you will not be " +
				"able to connect to peers labeled \"TOO NEW\" until you do.  " +
				"(Freenet may leave your node in the dust of the past if you don't upgrade.)";
		return s;
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", "This node\u2019s software is older than the oldest version (Build #" + lastGoodVersion + ") allowed by the newest peers we try to connect to. Please update your node as soon as possible because you will not be able to connect to peers labelled \u201cTOO NEW\u201d until you do. (Freenet may leave your node in the dust of the past if you don\u2019t upgrade.");
	}

	public short getPriorityClass() {
		return UserAlert.ERROR;
	}

	public boolean isValid() {
	  if(lastGoodVersion == 0)
	    return false;
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
