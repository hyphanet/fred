package freenet.node.updater;

import freenet.node.UserAlert;

public class RevocationKeyFoundUserAlert implements UserAlert {
	private final String msg;
	
	RevocationKeyFoundUserAlert(String msg){
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

	public short getPriorityClass() {
		return UserAlert.CRITICAL_ERROR;
	}
	
	public boolean isValid() {
		return true;
	}
	
	public void isValid(boolean b){
		// We ignore it : it's ALWAYS valid !
	}
}