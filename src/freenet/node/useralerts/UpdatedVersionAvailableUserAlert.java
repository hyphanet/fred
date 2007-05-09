/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.l10n.L10n;
import freenet.node.updater.NodeUpdateManager;
import freenet.node.updater.RevocationChecker;
import freenet.support.HTMLNode;

public class UpdatedVersionAvailableUserAlert implements UserAlert {
	private final NodeUpdateManager updater;

	public UpdatedVersionAvailableUserAlert(NodeUpdateManager updater){
		this.updater = updater;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return l10n("title");
	}

	private String l10n(String key) {
		return L10n.getString("UpdatedVersionAvailableUserAlert."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("UpdatedVersionAvailableUserAlert."+key, pattern, value);
	}

	private String l10n(String key, String[] patterns, String[] values) {
		return L10n.getString("UpdatedVersionAvailableUserAlert."+key, patterns, values);
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
		sb.append(l10n("notLatest"));
		sb.append(' ');
		
		if(updater.isArmed() && updater.inFinalCheck()) {
			sb.append(l10n("finalCheck", new String[] { "count", "max" }, 
					new String[] { Integer.toString(updater.getRevocationDNFCounter()), 
						Integer.toString(RevocationChecker.REVOCATION_DNF_MIN) }));
			sb.append(' ');
		} else if(updater.isArmed()) {
			sb.append(l10n("armed"));
		} else {
			String formText;
			if(updater.canUpdateNow()) {
				boolean b = false;
				if(updater.hasNewMainJar()) {
					sb.append(l10n("downloadedNewJar", "version", Integer.toString(updater.newMainJarVersion())));
					sb.append(' ');
					b = true;
				}
				if(updater.hasNewExtJar()) {
					sb.append(l10n(b ? "alsoDownloadedNewExtJar" : "downloadedNewExtJar", "version", Integer.toString(updater.newExtJarVersion())));
					sb.append(' ');
				}
				if(updater.canUpdateImmediately()) {
					sb.append(l10n("clickToUpdateNow"));
					formText = l10n("updateNowButton");
				} else {
					sb.append(l10n("clickToUpdateASAP"));
					formText = l10n("updateASAPButton");
				}
			} else {
				boolean fetchingNew = updater.fetchingNewMainJar();
				boolean fetchingNewExt = updater.fetchingNewExtJar();
				if(fetchingNew) {
					if(fetchingNewExt)
						sb.append(l10n("fetchingNewBoth", new String[] { "nodeVersion", "extVersion" },
								new String[] { Integer.toString(updater.fetchingNewMainJarVersion()), Integer.toString(updater.fetchingNewExtJarVersion()) }));
					else
						sb.append(l10n("fetchingNewNode", "nodeVersion", Integer.toString(updater.fetchingNewMainJarVersion())));
				} else {
					if(fetchingNewExt)
						sb.append(l10n("fetchingNewExt", "extVersion", Integer.toString(updater.fetchingNewMainJarVersion())));
				}
				sb.append(l10n("updateASAPQuestion"));
				formText = l10n("updateASAPButton");
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
		return L10n.getString("UserAlert.hide");
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return false;
	}
	
	public void onDismiss() {
		// Ignore
	}
}
