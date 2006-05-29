package freenet.node.useralerts;

public class IPUndetectedUserAlert implements UserAlert {
	boolean isValid=true;
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return "Unknown external address";
	}

	public String getText() {
		return "Freenet was unable to determine your external IP address " +
			"(or the IP address of your NAT or Firewall). You can still exchange " +
			"references with other people, however this will only work if the other " +
			"user is not behind a NAT or Firewall. As soon as you have connected to " +
			"one other user in this way, Freenet will be able to determine your external IP address.";
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
}
