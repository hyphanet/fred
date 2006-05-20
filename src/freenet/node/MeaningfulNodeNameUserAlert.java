package freenet.node;

public class MeaningfulNodeNameUserAlert implements UserAlert {
	private boolean isValid=true;

	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return "Your node name isn't defined";
	}

	public String getText() {
		return "It seems that your node's name isn't defined. Setting "+
		"up a node name doesn't affect your anonymity in any way but "+
		"is useful for your peers to know who you are in case they have "+
		"to reach you. You can change the node's name at the Configuration page. "+
		"Putting your e-mail address there is generally speaking a good idea."+
		"<br> Example : John Doe (bigbrother@nsa.gov) ";
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
}
