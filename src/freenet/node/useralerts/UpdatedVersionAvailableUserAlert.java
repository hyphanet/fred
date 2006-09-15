/*
  UpdatedVersionAvailableUserAlert.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.node.useralerts;

import freenet.node.updater.NodeUpdater;
import freenet.support.HTMLNode;

public class UpdatedVersionAvailableUserAlert implements UserAlert {
	private boolean isValid, isReady;
	private final NodeUpdater updater;
	private int version;
	private int readyVersion;

	public UpdatedVersionAvailableUserAlert(int version, NodeUpdater updater){
		this.version=version;
		isValid=false;
		isReady=false;
		this.updater = updater;
	}
	
	public synchronized void set(int availableVersion, int readyVersion, boolean ready){
		version = availableVersion;
		this.readyVersion = readyVersion;
		isReady = ready;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return "A new stable version of Freenet is available ("+version+")";
	}

	public String getText() {
		String s ="It seems that your node isn't running the latest version of the software. ";
		if(updater.inFinalCheck()) {
			return s + "Your node is currently doing a final check to verify the security of the update"+
			(version == readyVersion ? "" : (" to "+readyVersion)) +
			". ("+updater.getRevocationDNFCounter()+"/"+NodeUpdater.REVOCATION_DNF_MIN+")";
		} else {
			s+="Updating to "+version+" is advised. ";
			if(isReady) s += " <form action=\"/\" method=\"post\"><input type=\"submit\" name=\"update\" value=\"Update to "+readyVersion+" Now\" /></form>";
			if(readyVersion < version || !isReady)
				s += "Your node is currently fetching the update over Freenet. Once this is complete, you will be prompted to install the new version. Please be patient, this could take up to half an hour.";
			return s;
		}
	}

	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("#", "It seems that your node isn't running the latest version of the software.");
		if (updater.inFinalCheck()) {
			alertNode.addChild("#", " Your node is currently doing a final check to verify the security of the update " + (version == readyVersion ? "" : (" to " + readyVersion)) + ". (Finished checks: " + updater.getRevocationDNFCounter() + " of " + NodeUpdater.REVOCATION_DNF_MIN + ")");
		} else {
			if (isReady) {
				alertNode.addChild("#", " Updating to " + version + " is advised.");
				alertNode.addChild("form", new String[] { "action", "method" }, new String[] { "/", "post" }).addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "update", "Update to " + readyVersion + " now" });
				if(readyVersion < version)
					alertNode.addChild("#", "The node is currently fetching version "+version+" but you can update to "+readyVersion+" now, or wait for the node to fetch "+version+".");
			} else {
				if(updater.isAutoUpdateAllowed)
					alertNode.addChild("#", " Your node is currently fetching the update over Freenet and will automatically restart when it's ready (as configured).");
				else
					alertNode.addChild("#", " Your node is currently fetching the update over Freenet. Once this is complete, you will be prompted to install the new version. Please be patient, this could take up to half an hour.");
			}
		}
		return alertNode;
	}
	
	public short getPriorityClass() {
		if(isReady || updater.inFinalCheck())
			return UserAlert.WARNING;
		else
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
	
	public void onDismiss() {
		// do nothing on alert dismissal
	}
}
