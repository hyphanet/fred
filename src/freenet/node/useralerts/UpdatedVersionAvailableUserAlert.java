/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import java.io.File;

import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.updater.NodeUpdateManager;
import freenet.node.updater.RevocationChecker;
import freenet.support.HTMLNode;
import freenet.support.TimeUtil;

public class UpdatedVersionAvailableUserAlert extends AbstractUserAlert {
	private final NodeUpdateManager updater;

	public UpdatedVersionAvailableUserAlert(NodeUpdateManager updater){
		super(false, null, null, null, null, (short) 0, false, NodeL10n.getBase().getString("UserAlert.hide"), false, null);
		this.updater = updater;
	}
	
	@Override
	public String getTitle() {
		return l10n("title");
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("UpdatedVersionAvailableUserAlert."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("UpdatedVersionAvailableUserAlert."+key, pattern, value);
	}

	private String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("UpdatedVersionAvailableUserAlert."+key, patterns, values);
	}

	@Override
	public String getText() {
		
		UpdateThingy ut = createUpdateThingy();

		StringBuilder sb = new StringBuilder();
		
		sb.append(ut.firstBit);
		
		if(ut.formText != null) {
			sb.append(" <form action=\"/\" method=\"post\"><input type=\"submit\" name=\"update\" value=\"");
			sb.append(ut.formText);
			sb.append("\" /></form>");
		}
		
		return sb.toString();
	}
	
	@Override
	public String getShortText() {
		if(!updater.isArmed()) {
			if(updater.canUpdateNow()) {
				return l10n("shortReadyNotArmed");
			} else {
				return l10n("shortNotReadyNotArmed");
			}
		} else {
			return l10n("shortArmed");
		}
	}

	private static class UpdateThingy {
		public UpdateThingy(String first, String form) {
			this.firstBit = first;
			this.formText = form;
		}
		String firstBit;
		String formText;
	}
	
	@Override
	public HTMLNode getHTMLText() {
		
		UpdateThingy ut = createUpdateThingy();
		
		HTMLNode alertNode = new HTMLNode("div");
		
		alertNode.addChild("#", ut.firstBit);
		
		if(ut.formText != null) {
			alertNode.addChild("form", new String[] { "action", "method" }, new String[] { "/", "post" }).addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "update", ut.formText });
		}
		
		updater.renderProgress(alertNode);
		
		return alertNode;
	}
	
	private UpdateThingy createUpdateThingy() {
		StringBuilder sb = new StringBuilder();
		sb.append(l10n("notLatest"));
		sb.append(' ');
		
		if(updater.isArmed() && updater.inFinalCheck()) {
			sb.append(l10n("finalCheck", new String[] { "count", "max", "time" }, 
					new String[] { Integer.toString(updater.getRevocationDNFCounter()), 
						Integer.toString(RevocationChecker.REVOCATION_DNF_MIN), TimeUtil.formatTime(updater.timeRemainingOnCheck()) }));
			sb.append(' ');
		} else if(updater.isArmed()) {
			sb.append(l10n("armed"));
		} else {
			String formText;
			if(updater.canUpdateNow()) {
				if(updater.hasNewMainJar()) {
					sb.append(l10n("downloadedNewJar", "version", Integer.toString(updater.newMainJarVersion())));
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
				if(updater.fetchingFromUOM())
					sb.append(l10n("fetchingUOM", "updateScript", getUpdateScriptName()));
				else {
					boolean fetchingNew = updater.fetchingNewMainJar();
					if(fetchingNew) {
						sb.append(l10n("fetchingNewNode", "nodeVersion", Integer.toString(updater.fetchingNewMainJarVersion())));
					}
				}
				sb.append(" ");
				sb.append(l10n("updateASAPQuestion"));
				formText = l10n("updateASAPButton");
			}
			
			if(updater.node.updateIsUrgent()) {
				sb.append(" ");
				sb.append(l10n("updateIsUrgent"));
			}
			
			if(updater.brokenDependencies()) {
				sb.append(" ");
				sb.append(l10n("brokenDependencies", "version", Integer.toString(updater.newMainJarVersion())));
			}
			
			return new UpdateThingy(sb.toString(), formText);
		}
		
		return new UpdateThingy(sb.toString(), null);
	}

	private String getUpdateScriptName() {
		String name;
		if(File.separatorChar == '\\') {
			name = "update.cmd";
		} else {
			name = "update.sh";
		}
		File f = new File(updater.node.getNodeDir(), name);
		if(f.exists()) return f.toString();
		f = new File(new File(updater.node.getNodeDir(), "bin"), name);
		if(f.exists()) return f.toString();
		return name;
	}

	@Override
	public short getPriorityClass() {
		Node node = updater.node;
		if(node.updateIsUrgent())
			return UserAlert.CRITICAL_ERROR;
		if(updater.inFinalCheck() || updater.canUpdateNow() || !updater.isArmed())
			return UserAlert.ERROR;
		else
			return UserAlert.MINOR;
	}
	
	@Override
	public boolean isValid() {
		return updater.isEnabled() && (!updater.isBlown()) && 
			(updater.fetchingNewMainJar() || updater.hasNewMainJar() || updater.fetchingFromUOM());
	}
	
	@Override
	public void isValid(boolean b){
		// Ignore
	}
	
}
