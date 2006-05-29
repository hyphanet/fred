package freenet.node.useralerts;

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
}
