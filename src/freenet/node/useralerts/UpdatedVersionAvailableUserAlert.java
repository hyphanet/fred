/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.node.updater.NodeUpdaterManager;
import freenet.node.updater.RevocationChecker;
import freenet.support.HTMLNode;

public class UpdatedVersionAvailableUserAlert implements UserAlert {
	private final NodeUpdaterManager updater;

	public UpdatedVersionAvailableUserAlert(NodeUpdaterManager updater){
		this.updater = updater;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		StringBuffer sb = new StringBuffer();
		sb.append("A new stable version of Freenet is available (");
		appendVersionSummary(sb);
		sb.append(')');
		return sb.toString();
	}

	private void appendVersionSummary(StringBuffer sb) {
		boolean b = false;
		boolean main = false;
		boolean ext = false;
		if(updater.hasNewMainJar()) {
			sb.append("main jar ").append(updater.newMainJarVersion());
			b = true;
			main = true;
		}
		if(updater.fetchingNewMainJar()) {
			if(b) sb.append(", ");
			sb.append("fetching main jar ").append(updater.fetchingNewMainJarVersion());
			b = true;
			main = true;
		}
		if(main)
			sb.append(" from ").append(updater.getMainVersion());
		if(updater.hasNewExtJar()) {
			if(b) sb.append(", ");
			sb.append("extra jar ").append(updater.newExtJarVersion());
			b = true;
			ext = true;
		}
		if(updater.fetchingNewExtJar()) {
			if(b) sb.append(", ");
			sb.append("fetching extra jar ").append(updater.fetchingNewExtJarVersion());
			ext = true;
		}
		if(ext)
			sb.append(" from ").append(updater.getExtVersion());
	}

	public String getText() {
		
		UpdateThingy ut = createUpdateThingy();

		StringBuffer sb = new StringBuffer();
		
		sb.append(ut.firstBit);
		
		if(ut.formText != null) {
			sb.append(" <form action=\"/\" method=\"post\"><input type=\"submit\" name=\"update\" value=\"");
			sb.append(ut.formText);
			sb.append("\" /></form>");
		}
		
		return sb.toString();
	}

	class UpdateThingy {
		public UpdateThingy(String first, String form) {
			this.firstBit = first;
			this.formText = form;
		}
		String firstBit;
		String formText;
	}
	
	public HTMLNode getHTMLText() {
		
		UpdateThingy ut = createUpdateThingy();
		
		HTMLNode alertNode = new HTMLNode("div");
		
		alertNode.addChild("#", ut.firstBit);
		
		if(ut.formText != null) {
			alertNode.addChild("form", new String[] { "action", "method" }, new String[] { "/", "post" }).addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "update", ut.formText });
		}
		
		return alertNode;
	}
	
	private UpdateThingy createUpdateThingy() {
		StringBuffer sb = new StringBuffer();
		sb.append("It seems that your node isn't running the latest version of the software. ");
		
		if(updater.isArmed() && updater.inFinalCheck()) {
			sb.append("Your node is currently doing a final check to verify the security of the update (");
			sb.append(updater.getRevocationDNFCounter());
			sb.append('/');
			sb.append(RevocationChecker.REVOCATION_DNF_MIN);
			sb.append("). ");
		} else if(updater.isArmed()) {
			sb.append("Your node will automatically restart as soon as it has finished downloading and verifying the new version of Freenet.");
		} else {
			String formText;
			if(updater.canUpdateNow()) {
				boolean b = false;
				if(updater.hasNewMainJar()) {
					sb.append("Your node has downloaded a new version of Freenet, version ");
					sb.append(updater.newMainJarVersion());
					sb.append(". ");
					b = true;
				}
				if(updater.hasNewExtJar()) {
					if(b) {
						sb.append("Your node has also downloaded a new version of the Freenet extra jar, version ");
					} else {
						sb.append("Your node has downloaded a new version of the Freenet extra jar, version ");
					}
					sb.append(updater.newExtJarVersion());
					sb.append(". ");
				}
				sb.append("Click below to update your node");
				if(updater.canUpdateImmediately()) {
					sb.append(" immediately");
					formText = "Update Now!";
				} else { 
					sb.append(" as soon as the update has been verified");
					formText = "Update ASAP";
				}
				sb.append('.');
			} else {
				sb.append("Your node is currently downloading a new version of Freenet");
				boolean fetchingNew = updater.fetchingNewMainJar();
				boolean fetchingNewExt = updater.fetchingNewExtJar();
				if(fetchingNew || fetchingNewExt)
					sb.append(" (");
				if(fetchingNew) {
					sb.append("node version ");
					sb.append(updater.fetchingNewMainJarVersion());
				}
				if(fetchingNewExt) {
					if(fetchingNew)
						sb.append(", ");
					sb.append("ext version ");
					sb.append(updater.fetchingNewExtJarVersion());
				}
				if(fetchingNew)
					sb.append(')');
				sb.append(". ");
				sb.append("Would you like the node to automatically restart as soon as it has downloaded the update?");
				formText = "Update ASAP";
			}
			
			return new UpdateThingy(sb.toString(), formText);
		}
		
		return new UpdateThingy(sb.toString(), null);
	}

	public short getPriorityClass() {
		if(updater.inFinalCheck())
			return UserAlert.WARNING;
		else
			return UserAlert.MINOR;
	}
	
	public boolean isValid() {
		return updater.isEnabled() && (!updater.isBlown()) && updater.fetchingNewExtJar() || updater.fetchingNewMainJar() ||
			updater.hasNewExtJar() || updater.hasNewMainJar();
	}
	
	public void isValid(boolean b){
		// Ignore
	}
	
	public String dismissButtonText(){
		return "Hide";
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
	
	public void onDismiss() {
		// Ignore
	}
}
