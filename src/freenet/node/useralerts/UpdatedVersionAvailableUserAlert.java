package freenet.node.useralerts;

public class UpdatedVersionAvailableUserAlert implements UserAlert {
	private boolean isValid;
	private int version;

	public UpdatedVersionAvailableUserAlert(int version){
		this.version=version;
		isValid=false;
	}
	
	public synchronized void set(int v){
		version = v;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return "A new stable version of Freenet is available";
	}

	public String getText() {
		return "It seems that your node isn't running the latest version of the software. "+
		"Updating to "+version+" is advised. <form action=\"/\" method=\"post\">"+
		"<input type=\"submit\" name=\"update\" value=\"Update Now\" /></form>";
	}
	
	public short getPriorityClass() {
		return UserAlert.MINOR;
	}
	
	public boolean isValid() {
		return isValid;
	}
	
	public void isValid(boolean b){
		isValid=b;
	}
	
	public String dismissButtonText(){
		return "Hide";
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
}
