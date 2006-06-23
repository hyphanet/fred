package freenet.node.useralerts;

import freenet.node.updater.NodeUpdater;

public class UpdatedVersionAvailableUserAlert implements UserAlert {
	private boolean isValid, isReady;
	private NodeUpdater updater;
	private int version;

	public UpdatedVersionAvailableUserAlert(int version, NodeUpdater updater){
		this.version=version;
		isValid=false;
		isReady=false;
		this.updater = updater;
	}
	
	public synchronized void set(int v, boolean ready){
		version = v;
		isReady = ready;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return "A new stable version of Freenet is available";
	}

	public String getText() {
		String s ="It seems that your node isn't running the latest version of the software. "+
		"Updating to "+version+" is advised. ";
		
		if(updater.inFinalCheck()) {
			return s + "Your node is currently doing a final check to verify the security of the update.";
		} else {
			if(isReady) return s+
				" <form action=\"/\" method=\"post\"><input type=\"submit\" name=\"update\" value=\"Update Now\" /></form>";
			else return s+
				"Your node is currently fetching the update and will ask you whatever you want to update or not when it's ready.";
		}
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
