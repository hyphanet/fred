package freenet.node.useralerts;

import freenet.support.HTMLNode;

public class RevocationKeyFoundUserAlert implements UserAlert {
	private final String msg;
	
	public RevocationKeyFoundUserAlert(String msg){
		this.msg=msg;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return "The private key of the project has been compromized!";
	}

	public String getText() {
		//TODO: reformulate : maybe put the GPG key fingerprint of "trusted devs"
		return "Your node has found the auto-updater's revocation key on the network. "+
			"It means that our auto-updating system is likely to have been COMPROMIZED! "+
			"Consequently, it has been disabled on your node to prevent \"bad things\" to "+
			"be installed. We strongly advise you to check the project's website for updates. "+
			"Please take care of verifying that the website hasn't been spoofed either. "+
			"The revocation message is the following : "+msg;
	}

	public HTMLNode getHTMLText() {
		return new HTMLNode("div", "Your node has found the audo-updater\u2019s revocation key on the network. It means that our auto-updating system is likely to have been COMPROMIZED! Consequently, it has been disabled on your node to prevent \u201cbad things\u201d to be installed. We strongly advise you to check the project\u2019s website for updates. Please take care of verifying that the website hasn't been spoofed either. The revocation message is the following: " + msg);
	}

	public short getPriorityClass() {
		return UserAlert.CRITICAL_ERROR;
	}
	
	public boolean isValid() {
		return true;
	}
	
	public void isValid(boolean b){
		// We ignore it : it's ALWAYS valid !
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
