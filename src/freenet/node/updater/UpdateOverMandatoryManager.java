/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.Vector;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.node.PeerNode;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.Logger;

/**
 * Co-ordinates update over mandatory. Update over mandatory = updating from your peers, even
 * though they may be so much newer than you that you can't route requests through them.
 * NodeDispatcher feeds UOMAnnounce's received from peers to this class, and it decides what to
 * do about them.
 * @author toad
 */
public class UpdateOverMandatoryManager {

	final NodeUpdateManager updateManager;
	
	/** Set of WeakReferences to PeerNode's which say (or said before they disconnected) 
	 * the key has been revoked */
	private final HashSet nodesSayKeyRevoked;
	/** Set of WeakReferences to PeerNode's which say the key has been revoked but failed
	 * to transfer the revocation key. */
	private final HashSet nodesSayKeyRevokedFailedTransfer;
	
	private UserAlert alert;
	
	public UpdateOverMandatoryManager(NodeUpdateManager manager) {
		this.updateManager = manager;
		nodesSayKeyRevoked = new HashSet();
		nodesSayKeyRevokedFailedTransfer = new HashSet();
	}

	/** 
	 * Handle a UOMAnnounce message. A node has sent us a message offering us use of its update
	 * over mandatory facilities in some way.
	 * @param m The message to handle.
	 * @param source The PeerNode which sent the message.
	 * @return True unless we don't want the message (in this case, always true).
	 */
	public boolean handleAnnounce(Message m, final PeerNode source) {
		String jarKey = m.getString(DMT.MAIN_JAR_KEY);
		String extraJarKey = m.getString(DMT.EXTRA_JAR_KEY);
		String revocationKey = m.getString(DMT.REVOCATION_KEY);
		boolean haveRevocationKey = m.getBoolean(DMT.HAVE_REVOCATION_KEY);
		long mainJarVersion = m.getLong(DMT.MAIN_JAR_VERSION);
		long extraJarVersion = m.getLong(DMT.EXTRA_JAR_VERSION);
		long revocationKeyLastTried = m.getLong(DMT.REVOCATION_KEY_TIME_LAST_TRIED);
		int revocationKeyDNFs = m.getInt(DMT.REVOCATION_KEY_DNF_COUNT);
		long revocationKeyFileLength = m.getLong(DMT.REVOCATION_KEY_FILE_LENGTH);
		long mainJarFileLength = m.getLong(DMT.MAIN_JAR_FILE_LENGTH);
		long extraJarFileLength = m.getLong(DMT.EXTRA_JAR_FILE_LENGTH);
		int pingTime = m.getInt(DMT.PING_TIME);
		int delayTime = m.getInt(DMT.BWLIMIT_DELAY_TIME);
		
		// Log it
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) {
			Logger.minor(this, "Update Over Mandatory offer from node "+source.getPeer()+" : "+source.getName()+":");
			Logger.minor(this, "Main jar key: "+jarKey+" version="+mainJarVersion+" length="+mainJarFileLength);
			Logger.minor(this, "Extra jar key: "+extraJarKey+" version="+extraJarVersion+" length="+extraJarFileLength);
			Logger.minor(this, "Revocation key: "+revocationKey+" found="+haveRevocationKey+" length="+revocationKeyFileLength+" last had 3 DNFs "+revocationKeyLastTried+" ms ago, "+revocationKeyDNFs+" DNFs so far");
			Logger.minor(this, "Load stats: "+pingTime+"ms ping, "+delayTime+"ms bwlimit delay time");
		}
		
		// Now the core logic
		
		// First off, if a node says it has the revocation key, and its key is the same as ours,
		// we should 1) suspend any auto-updates and tell the user, 2) try to download it, and 
		// 3) if the download fails, move the notification; if the download succeeds, process it
		
		if(haveRevocationKey) {
			// First, is the key the same as ours?
			try {
				FreenetURI revocationURI = new FreenetURI(revocationKey);
				if(/*revocationURI.equals(updateManager.revocationURI)*/true) {
					
					// Uh oh...
					
					// Have to do this first to avoid race condition
					synchronized(this) {
						nodesSayKeyRevoked.add(source);
					}
					
					// Disable the update
					updateManager.peerClaimsKeyBlown(source);
					
					// Tell the user
					alertUser();
					
					System.err.println("Your peer "+source.getPeer()+" : "+source.getName()+" says that the auto-update key is blown!");
					System.err.println("Attempting to fetch it...");
					
					// Try to transfer it.
					
					Message msg = DMT.createUOMRequestRevocation();
					source.sendAsync(msg, new AsyncMessageCallback() {
						public void acknowledged() {
							// Ok
						}
						public void disconnected() {
							// :(
							System.err.println("Failed to send request for revocation key to "+source.getPeer()+" : "+source.getName()+" because it disconnected!");
							synchronized(UpdateOverMandatoryManager.this) {
								nodesSayKeyRevokedFailedTransfer.add(source);
							}
						}
						public void fatalError() {
							// Not good!
							System.err.println("Failed to send request for revocation key to "+source.getPeer()+" : "+source.getName()+" because of a fatal error.");
						}
						public void sent() {
							// Cool
						}
					}, 0, null);
					
					// The reply message will start the transfer. It includes the revocation URI
					// so we can tell if anything wierd is happening.
					
				} else {
					// Should probably also be a useralert?
					Logger.normal(this, "Node "+source+" sent us a UOM claiming that the auto-update key was blown, but it used a different key to us: \nour key="+updateManager.revocationURI+"\nhis key="+revocationURI);
					System.err.println("Node "+source.getPeer()+" : "+source.getName()+" sent us a UOM claiming that the revocation key was blown, but it used a different key to us: \nour key="+updateManager.revocationURI+"\nhis key="+revocationURI);
				}
			} catch (MalformedURLException e) {
				// Should maybe be a useralert?
				Logger.error(this, "Node "+source+" sent us a UOMAnnounce claiming that the auto-update key was blown, but it had an invalid revocation URI: "+revocationKey+" : "+e, e);
				System.err.println("Node "+source.getPeer()+" : "+source.getName()+" sent us a UOMAnnounce claiming that the revocation key was blown, but it had an invalid revocation URI: "+revocationKey+" : "+e);
			} catch (NotConnectedException e) {
				System.err.println("Node "+source+" says that the auto-update key was blown, but has now gone offline! Something BAD is happening!");
				Logger.error(this, "Node "+source+" says that the auto-update key was blown, but has now gone offline! Something BAD is happening!");
				synchronized(UpdateOverMandatoryManager.this) {
					nodesSayKeyRevokedFailedTransfer.add(source);
				}
			}
			
		}
		
		return true;
	}

	private void alertUser() {
		synchronized(this) {
			if(alert != null) return;
			alert = new PeersSayKeyBlownAlert();
		}
		updateManager.node.clientCore.alerts.register(alert);
	}

	class PeersSayKeyBlownAlert implements UserAlert {

		public String dismissButtonText() {
			// Cannot dismiss
			return null;
		}

		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			
			div.addChild("p").addChild("#", l10n("intro"));
			
			PeerNode[][] nodes = getNodesSayBlown();
			PeerNode[] nodesSayBlownConnected = nodes[0];
			PeerNode[] nodesSayBlownDisconnected = nodes[1];
			PeerNode[] nodesSayBlownFailedTransfer = nodes[2];
			
			if(nodesSayBlownConnected.length > 0) {
				div.addChild("p").addChild("#", l10n("fetching"));
			} else {
				div.addChild("p").addChild("#", l10n("failedFetch"));
			}
			
			if(nodesSayBlownConnected.length > 0) {
				div.addChild("p").addChild("#", l10n("connectedSayBlownLabel"));
				HTMLNode list = div.addChild("ul");
				for(int i=0;i<nodesSayBlownConnected.length;i++) {
					list.addChild("li", nodesSayBlownConnected[i].getName()+" ("+nodesSayBlownConnected[i].getPeer()+")");
				}
			}
			
			if(nodesSayBlownDisconnected.length > 0) {
				div.addChild("p").addChild("#", l10n("disconnectedSayBlownLabel"));
				HTMLNode list = div.addChild("ul");
				for(int i=0;i<nodesSayBlownDisconnected.length;i++) {
					list.addChild("li", nodesSayBlownDisconnected[i].getName()+" ("+nodesSayBlownDisconnected[i].getPeer()+")");
				}
			}
			
			if(nodesSayBlownFailedTransfer.length > 0) {
				div.addChild("p").addChild("#", l10n("failedTransferSayBlownLabel"));
				HTMLNode list = div.addChild("ul");
				for(int i=0;i<nodesSayBlownFailedTransfer.length;i++) {
					list.addChild("li", nodesSayBlownFailedTransfer[i].getName()+" ("+nodesSayBlownFailedTransfer[i].getPeer()+")");
				}
			}
			
			return div;
		}

		private String l10n(String key) {
			return L10n.getString("PeersSayKeyBlownAlert."+key);
		}
		
		private String l10n(String key, String pattern, String value) {
			return L10n.getString("PeersSayKeyBlownAlert."+key, pattern, value);
		}
		
		public short getPriorityClass() {
			return UserAlert.CRITICAL_ERROR;
		}

		public String getText() {
			StringBuffer sb = new StringBuffer();
			sb.append(l10n("intro")).append("\n\n");
			PeerNode[][] nodes = getNodesSayBlown();
			PeerNode[] nodesSayBlownConnected = nodes[0];
			PeerNode[] nodesSayBlownDisconnected = nodes[1];
			PeerNode[] nodesSayBlownFailedTransfer = nodes[2];
			
			if(nodesSayBlownConnected.length > 0) {
				sb.append(l10n("fetching")).append("\n\n");
			} else {
				sb.append(l10n("failedFetch")).append("\n\n");
			}
			
			if(nodesSayBlownConnected.length > 0) {
				sb.append(l10n("connectedSayBlownLabel")).append("\n\n");
				for(int i=0;i<nodesSayBlownConnected.length;i++) {
					sb.append(nodesSayBlownConnected[i].getName()+" ("+nodesSayBlownConnected[i].getPeer()+")").append("\n");
				}
				sb.append("\n");
			}
			
			if(nodesSayBlownDisconnected.length > 0) {
				sb.append(l10n("disconnectedSayBlownLabel"));
				
				for(int i=0;i<nodesSayBlownDisconnected.length;i++) {
					sb.append(nodesSayBlownDisconnected[i].getName()+" ("+nodesSayBlownDisconnected[i].getPeer()+")").append("\n");
				}
				sb.append("\n");
			}
			
			if(nodesSayBlownFailedTransfer.length > 0) {
				sb.append(l10n("failedTransferSayBlownLabel"));
				
				for(int i=0;i<nodesSayBlownFailedTransfer.length;i++) {
					sb.append(nodesSayBlownFailedTransfer[i].getName()+" ("+nodesSayBlownFailedTransfer[i].getPeer()+")").append('\n');
				}
				sb.append("\n");
			}
			
			return sb.toString();
		}

		public String getTitle() {
			return l10n("titleWithCount", "count", Integer.toString(nodesSayKeyRevoked.size()));
		}

		public boolean isValid() {
			return true;
		}

		public void isValid(boolean validity) {
			// Do nothing
		}

		public void onDismiss() {
			// Do nothing
		}

		public boolean shouldUnregisterOnDismiss() {
			// Can't dismiss
			return false;
		}

		public boolean userCanDismiss() {
			// Can't dismiss
			return false;
		}
		
	}

	public PeerNode[][] getNodesSayBlown() {
		Vector nodesConnectedSayRevoked = new Vector();
		Vector nodesDisconnectedSayRevoked = new Vector();
		Vector nodesFailedSayRevoked = new Vector();
		synchronized(this) {
			PeerNode[] nodesSayRevoked = (PeerNode[]) nodesSayKeyRevoked.toArray(new PeerNode[nodesSayKeyRevoked.size()]);
			for(int i=0;i<nodesSayRevoked.length;i++) {
				PeerNode pn = nodesSayRevoked[i];
				if(nodesSayKeyRevokedFailedTransfer.contains(pn))
					nodesFailedSayRevoked.add(pn);
				else
					nodesConnectedSayRevoked.add(pn);
			}
		}
		for(int i=0;i<nodesConnectedSayRevoked.size();i++) {
			PeerNode pn = (PeerNode) nodesConnectedSayRevoked.get(i);
			if(!pn.isConnected()) {
				nodesDisconnectedSayRevoked.add(pn);
				nodesConnectedSayRevoked.remove(i);
				i--;
				continue;
			}
		}
		return new PeerNode[][] {
				(PeerNode[]) nodesConnectedSayRevoked.toArray(new PeerNode[nodesConnectedSayRevoked.size()]),
				(PeerNode[]) nodesDisconnectedSayRevoked.toArray(new PeerNode[nodesDisconnectedSayRevoked.size()]),
				(PeerNode[]) nodesFailedSayRevoked.toArray(new PeerNode[nodesFailedSayRevoked.size()]),
		};
	}
	
}
