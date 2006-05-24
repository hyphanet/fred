package freenet.node.useralerts;

import freenet.node.Node;

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
		"Updating to "+version+" is advised.";
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
}
