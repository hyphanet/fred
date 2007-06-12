/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.Vector;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.BinaryBlob;
import freenet.client.async.BinaryBlobFormatException;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.client.async.SimpleBlockSet;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.BulkReceiver;
import freenet.io.xfer.BulkTransmitter;
import freenet.io.xfer.PartiallyReceivedBulk;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.node.RequestStarter;
import freenet.node.Version;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.io.FileBucket;
import freenet.support.io.RandomAccessFileWrapper;

/**
 * Co-ordinates update over mandatory. Update over mandatory = updating from your peers, even
 * though they may be so much newer than you that you can't route requests through them.
 * NodeDispatcher feeds UOMAnnounce's received from peers to this class, and it decides what to
 * do about them.
 * @author toad
 */
public class UpdateOverMandatoryManager {

	final NodeUpdateManager updateManager;
	
	/** Set of PeerNode's which say (or said before they disconnected) 
	 * the key has been revoked */
	private final HashSet nodesSayKeyRevoked;
	/** Set of PeerNode's which say the key has been revoked but failed
	 * to transfer the revocation key. */
	private final HashSet nodesSayKeyRevokedFailedTransfer;
	/** PeerNode's which have offered the main jar which we are not fetching it from right now */
	private final HashSet nodesOfferedMainJar;
	/** PeerNode's we've asked to send the main jar */
	private final HashSet nodesAskedSendMainJar;
	/** PeerNode's sending us the main jar */
	private final HashSet nodesSendingMainJar;
	// 2 for reliability, no more as gets very slow/wasteful
	static final int MAX_NODES_SENDING_MAIN_JAR = 2;
	/** Maximum time between asking for the main jar and it starting to transfer */
	static final int REQUEST_MAIN_JAR_TIMEOUT = 60*1000;
	
	private UserAlert alert;
	
	public UpdateOverMandatoryManager(NodeUpdateManager manager) {
		this.updateManager = manager;
		nodesSayKeyRevoked = new HashSet();
		nodesSayKeyRevokedFailedTransfer = new HashSet();
		nodesOfferedMainJar = new HashSet();
		nodesAskedSendMainJar = new HashSet();
		nodesSendingMainJar = new HashSet();
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
			
			if(updateManager.isBlown()) return true; // We already know
			
			// First, is the key the same as ours?
			try {
				FreenetURI revocationURI = new FreenetURI(revocationKey);
				if(revocationURI.equals(updateManager.revocationURI)) {
					
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
					
					Message msg = DMT.createUOMRequestRevocation(updateManager.node.random.nextLong());
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
		
		if(updateManager.isBlown()) return true; // We already know
		
		if(!updateManager.isEnabled()) return true; // Don't care if not enabled, except for the revocation URI
		
		if(mainJarVersion > Version.buildNumber() && mainJarFileLength > 0) {
			// Fetch it
			try {
				FreenetURI mainJarURI = new FreenetURI(jarKey);
				if(mainJarURI.equals(updateManager.updateURI)) {
					System.err.println("Fetching main jar from "+source.userToString());
					sendUOMRequestMain(source);
				}
			} catch (MalformedURLException e) {
				// Should maybe be a useralert?
				Logger.error(this, "Node "+source+" sent us a UOMAnnounce claiming to have a new jar, but it had an invalid URI: "+revocationKey+" : "+e, e);
				System.err.println("Node "+source.getPeer()+" : "+source.getName()+" sent us a UOMAnnounce claiming to have a new jar, but it had an invalid URI: "+revocationKey+" : "+e);
			}
		}
		
		return true;
	}

	protected void sendUOMRequestMain(final PeerNode source) {
		Message msg = DMT.createUOMRequestMain(updateManager.node.random.nextLong());
		
		try {
			synchronized(this) {
				if(nodesAskedSendMainJar.size() + nodesSendingMainJar.size() >= MAX_NODES_SENDING_MAIN_JAR) {
					nodesOfferedMainJar.add(source);
					return;
				} else {
					nodesAskedSendMainJar.add(source);
				}
			}
			source.sendAsync(msg, new AsyncMessageCallback() {
				public void acknowledged() {
					// Cool! Wait for the actual transfer.
				}
				public void disconnected() {
					Logger.normal(this, "Disconnected from "+source.userToString()+" after sending UOMRequestMain");
					synchronized(UpdateOverMandatoryManager.this) {
						nodesAskedSendMainJar.remove(source);
					}
					maybeRequestMainJar();
				}
				public void fatalError() {
					Logger.normal(this, "Fatal error from "+source.userToString()+" after sending UOMRequestMain");
					synchronized(UpdateOverMandatoryManager.this) {
						nodesAskedSendMainJar.remove(source);
					}
					maybeRequestMainJar();
				}
				public void sent() {
					// Timeout...
					updateManager.node.ps.queueTimedJob(new Runnable() {
						public void run() {
							synchronized(UpdateOverMandatoryManager.this) {
								if(!nodesAskedSendMainJar.contains(source)) return;
								nodesAskedSendMainJar.remove(source); // free up a slot
							}
							maybeRequestMainJar();
						}
					}, REQUEST_MAIN_JAR_TIMEOUT);
				}
			}, 0, null);
		} catch (NotConnectedException e) {
			synchronized(this) {
				nodesAskedSendMainJar.remove(source);
			}
			maybeRequestMainJar();
		}
	}
	
	
	protected void maybeRequestMainJar() {
		while(true) {
			PeerNode pn;
			synchronized(this) {
				if(nodesAskedSendMainJar.size() + nodesSendingMainJar.size() 
						>= MAX_NODES_SENDING_MAIN_JAR)
					return;
				if(nodesOfferedMainJar.isEmpty()) return;
				pn = (PeerNode) (nodesOfferedMainJar.iterator().next());
			}
			if(!pn.isConnected()) continue;
			sendUOMRequestMain(pn);
		}
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

	/**
	 * A peer node requests us to send the binary blob of the revocation key.
	 * @param m The message requesting the transfer.
	 * @param source The node requesting the transfer.
	 * @return True if we handled the message (i.e. always).
	 */
	public boolean handleRequestRevocation(Message m, final PeerNode source) {
		// Do we have the data?
		
		File data = updateManager.revocationChecker.getBlobFile();
		
		if(data == null) {
			Logger.normal(this, "Peer "+source+" asked us for the blob file for the revocation key but we don't have it!");
			// Probably a race condition on reconnect, hopefully we'll be asked again
			return true;
		}
		
		final long uid = m.getLong(DMT.UID);
		
		RandomAccessFileWrapper raf; 
		try {
			raf = new RandomAccessFileWrapper(data, "r");
		} catch (FileNotFoundException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the revocation key, we have downloaded it but don't have the file even though we did have it when we checked!: "+e, e);
			return true;
		}
		
		final PartiallyReceivedBulk prb;
		long length;
		try {
			length = raf.size();
			prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length, 
					Node.PACKET_SIZE, raf, true);
		} catch (IOException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the revocation key, we have downloaded it but we can't determine the file size: "+e, e);
			return true;
		}
		
		final BulkTransmitter bt;
		try {
			bt = new BulkTransmitter(prb, source, uid, updateManager.node.outputThrottle);
		} catch (DisconnectedException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the revocation key, then disconnected: "+e, e);
			return true;
		}
		
		final Runnable r = new Runnable() {
			public void run() {
				if(!bt.send()) {
					Logger.error(this, "Failed to send revocation key blob to "+source.getPeer()+" : "+source.getName());
				} else {
					Logger.normal(this, "Sent revocation key blob to "+source.getPeer()+" : "+source.getName());
				}
			}
			
		};
		
		Message msg = DMT.createUOMSendingRevocation(uid, length, updateManager.revocationURI.toString());
		
		try {
			source.sendAsync(msg, new AsyncMessageCallback() {
				public void acknowledged() {
					if(Logger.shouldLog(Logger.MINOR, this))
						Logger.minor(this, "Sending data...");
					// Send the data
					Thread t = new Thread(r, "Revocation key send for "+uid+" to "+source.getPeer()+" : "+source.getName());
					t.setDaemon(true);
					t.start();
				}
				public void disconnected() {
					// Argh
					Logger.error(this, "Peer "+source+" asked us for the blob file for the revocation key, then disconnected when we tried to send the UOMSendingRevocation");				
				}

				public void fatalError() {
					// Argh
					Logger.error(this, "Peer "+source+" asked us for the blob file for the revocation key, then got a fatal error when we tried to send the UOMSendingRevocation");				
				}

				public void sent() {
					if(Logger.shouldLog(Logger.MINOR, this))
						Logger.minor(this, "Message sent, data soon");
				}
				
				public String toString() {
					return super.toString() + "("+uid+":"+source.getPeer()+")";
				}
				
			}, 0, null);
		} catch (NotConnectedException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the revocation key, then disconnected when we tried to send the UOMSendingRevocation: "+e, e);
			return true;
		}
		
		return true;
	}

	public boolean handleSendingRevocation(Message m, final PeerNode source) {
		final long uid = m.getLong(DMT.UID);
		final long length = m.getLong(DMT.FILE_LENGTH);
		String key = m.getString(DMT.REVOCATION_KEY);
		FreenetURI revocationURI;
		try {
			revocationURI = new FreenetURI(key);
		} catch (MalformedURLException e) {
			Logger.error(this, "Failed receiving recovation because URI not parsable: "+e+" for "+key, e);
			System.err.println("Failed receiving recovation because URI not parsable: "+e+" for "+key);
			e.printStackTrace();
			cancelSend(source, uid);
			return true;
		}
		
		if(!revocationURI.equals(updateManager.revocationURI)) {
			System.err.println("Node sending us a revocation certificate from the wrong URI:\n"+
					"Node: "+source.getPeer()+" : "+source.getName()+"\n"+
					"Our   URI: "+updateManager.revocationURI+"\n"+
					"Their URI: "+revocationURI);
			cancelSend(source, uid);
			return true;
		}
		
		if(updateManager.isBlown()) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Already blown, so not receiving from "+source+ "("+uid+")");
			cancelSend(source, uid);
			return true;
		}
		
		if(length > NodeUpdateManager.MAX_REVOCATION_KEY_LENGTH) {
			System.err.println("Node "+source.getPeer()+" : "+source.getName()+" offered us a revocation certificate "+SizeUtil.formatSize(length)+" long. This is unacceptably long so we have refused the transfer.");
			Logger.error(this, "Node "+source.getPeer()+" : "+source.getName()+" offered us a revocation certificate "+SizeUtil.formatSize(length)+" long. This is unacceptably long so we have refused the transfer.");
			synchronized(UpdateOverMandatoryManager.this) {
				nodesSayKeyRevokedFailedTransfer.add(source);
			}
			cancelSend(source, uid);
			return true;
		}
		
		// Okay, we can receive it
		
		final File temp;
		
		try {
			temp = File.createTempFile("revocation-", ".fblob.tmp", updateManager.node.getNodeDir());
			temp.deleteOnExit();
		} catch (IOException e) {
			System.err.println("Cannot save revocation certificate to disk and therefore cannot fetch it from our peer!: "+e);
			e.printStackTrace();
			updateManager.blow("Cannot fetch the revocation certificate from our peer because we cannot write it to disk: "+e);
			cancelSend(source, uid);
			return true;
		}
		
		RandomAccessFileWrapper raf; 
		try {
			raf = new RandomAccessFileWrapper(temp, "rw");
		} catch (FileNotFoundException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the revocation key, we have downloaded it but don't have the file even though we did have it when we checked!: "+e, e);
			return true;
		}
		
		PartiallyReceivedBulk prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length, 
				Node.PACKET_SIZE, raf, false);
		
		final BulkReceiver br = new BulkReceiver(prb, source, uid);
		
		Thread t = new Thread(new Runnable() {

			public void run() {
				if(br.receive()) {
					// Success!
					processRevocationBlob(temp, source);
				} else {
					Logger.error(this, "Failed to transfer revocation certificate from "+source);
					System.err.println("Failed to transfer revocation certificate from "+source);
					synchronized(UpdateOverMandatoryManager.this) {
						nodesSayKeyRevokedFailedTransfer.add(source);
					}
				}
			}
			
		}, "Revocation key receive for "+uid+" from "+source.getPeer()+" : "+source.getName());
		
		t.setDaemon(true);
		t.start();
		
		return true;
	}

	/**
	 * Process a binary blob for a revocation certificate (the revocation key).
	 * @param temp The file it was written to.
	 */
	protected void processRevocationBlob(final File temp, final PeerNode source) {
		
		SimpleBlockSet blocks = new SimpleBlockSet();
		
		DataInputStream dis = null;
		try {
			dis = new DataInputStream(new BufferedInputStream(new FileInputStream(temp)));
			BinaryBlob.readBinaryBlob(dis, blocks, true);
		} catch (FileNotFoundException e) {
			Logger.error(this, "Somebody deleted "+temp+" ? We lost the revocation certificate from "+source.userToString()+"!");
			System.err.println("Somebody deleted "+temp+" ? We lost the revocation certificate from "+source.userToString()+"!");
			return;
		} catch (IOException e) {
			Logger.error(this, "Could not read revocation cert from temp file "+temp+" from node "+source.userToString()+" !");
			System.err.println("Could not read revocation cert from temp file "+temp+" from node "+source.userToString()+" !");
			// FIXME will be kept until exit for debugging purposes
			return;
		} catch (BinaryBlobFormatException e) {
			Logger.error(this, "Peer "+source.userToString()+" sent us an invalid revocation certificate!: "+e, e);
			System.err.println("Peer "+source.userToString()+" sent us an invalid revocation certificate!: "+e);
			e.printStackTrace();
			synchronized(UpdateOverMandatoryManager.this) {
				nodesSayKeyRevokedFailedTransfer.add(source);
			}
			// FIXME will be kept until exit for debugging purposes
			return;
		} finally {
			if(dis != null)
				try {
					dis.close();
				} catch (IOException e) {
					// Ignore
				}
		}
		
		// Fetch our revocation key from the datastore plus the binary blob
		
		FetchContext tempContext = updateManager.node.clientCore.makeClient((short)0, true).getFetchContext();		
		tempContext.localRequestOnly = true;
		tempContext.blocks = blocks;
		
		File f;
		FileBucket b = null;
		try {
			f = File.createTempFile("revocation-", ".fblob.tmp", updateManager.node.getNodeDir());
			b = new FileBucket(f, false, false, true, true, true);
		} catch (IOException e) {
			Logger.error(this, "Cannot share revocation key from "+source.userToString()+" with our peers because cannot write the cleaned version to disk: "+e, e);
			System.err.println("Cannot share revocation key from "+source.userToString()+" with our peers because cannot write the cleaned version to disk: "+e);
			e.printStackTrace();
			b = null;
			f = null;
		}
		final FileBucket cleanedBlob = b;
		final File cleanedBlobFile = f;
		
		ClientCallback myCallback = new ClientCallback() {

			public void onFailure(FetchException e, ClientGetter state) {
				if(e.mode == FetchException.CANCELLED) {
					// Eh?
					Logger.error(this, "Cancelled fetch from store/blob of revocation certificate from "+source.userToString());
					System.err.println("Cancelled fetch from store/blob of revocation certificate from "+source.userToString()+" to "+temp+" - please report to developers");
					// Probably best to keep files around for now.
				} else if(e.isFatal()) {
					// Blown: somebody inserted a revocation message, but it was corrupt as inserted
					// However it had valid signatures etc.
					
					System.err.println("Got revocation certificate from "+source.userToString()+" (fatal error i.e. someone with the key inserted bad data)");
					// Blow the update, and propagate the revocation certificate.
					updateManager.revocationChecker.onFailure(e, state, cleanedBlobFile);
					temp.delete();
					
					insertBlob(updateManager.revocationChecker.getBlobFile());
					
				} else {
					Logger.error(this, "Failed to fetch revocation certificate from blob from "+source.userToString());
					System.err.println("Failed to fetch revocation certificate from blob from "+source.userToString());
					synchronized(UpdateOverMandatoryManager.this) {
						nodesSayKeyRevokedFailedTransfer.add(source);
					}
				}
			}

			public void onFailure(InsertException e, BaseClientPutter state) {
				// Ignore, not possible
			}

			public void onFetchable(BaseClientPutter state) {
				// Irrelevant
			}

			public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
				// Ignore, not possible
			}

			public void onMajorProgress() {
				// Ignore
			}

			public void onSuccess(FetchResult result, ClientGetter state) {
				System.err.println("Got revocation certificate from "+source.userToString());
				updateManager.revocationChecker.onSuccess(result, state, cleanedBlobFile);
				temp.delete();
				insertBlob(updateManager.revocationChecker.getBlobFile());
			}

			public void onSuccess(BaseClientPutter state) {
				// Ignore, not possible
			}
			
		};
		
		ClientGetter cg = new ClientGetter(myCallback, 
				updateManager.node.clientCore.requestStarters.chkFetchScheduler,
				updateManager.node.clientCore.requestStarters.sskFetchScheduler, 
				updateManager.revocationURI, tempContext, (short)0, this, null, cleanedBlob); 
		
		try {
			cg.start();
		} catch (FetchException e1) {
			myCallback.onFailure(e1, cg);
		}
		
	}

	protected void insertBlob(final File blob) {
		ClientCallback callback = new ClientCallback() {
			public void onFailure(FetchException e, ClientGetter state) {
				// Ignore, can't happen
			}
			public void onFailure(InsertException e, BaseClientPutter state) {
				Logger.error(this, "Failed to insert revocation key binary blob: "+e, e);
			}
			public void onFetchable(BaseClientPutter state) {
				// Ignore
			}
			public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
				// Ignore
			}
			public void onMajorProgress() {
				// Ignore
			}
			public void onSuccess(FetchResult result, ClientGetter state) {
				// Ignore, can't happen
			}
			public void onSuccess(BaseClientPutter state) {
				// All done. Cool.
				Logger.normal(this, "Inserted binary blob for revocation key");
			}
		};
		FileBucket bucket = new FileBucket(blob, true, false, false, false, false);
		ClientPutter putter = new ClientPutter(callback, bucket,
				FreenetURI.EMPTY_CHK_URI, null, updateManager.node.clientCore.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS).getInsertContext(true),
				updateManager.node.clientCore.requestStarters.chkPutScheduler,
				updateManager.node.clientCore.requestStarters.sskPutScheduler,
				RequestStarter.INTERACTIVE_PRIORITY_CLASS, false, false, this, null, null, true);
		try {
			putter.start(false);
		} catch (InsertException e1) {
			Logger.error(this, "Failed to start insert of revocation key binary blob: "+e1, e1);
		}
	}

	private void cancelSend(PeerNode source, long uid) {
		Message msg = DMT.createFNPBulkReceiveAborted(uid);
		try {
			source.sendAsync(msg, null, 0, null);
		} catch (NotConnectedException e1) {
			// Ignore
		}
	}

	public void killAlert() {
		updateManager.node.clientCore.alerts.unregister(alert);
	}

	public boolean handleRequestMain(Message m, final PeerNode source) {
		// Do we have the data?
		
		int version = updateManager.newMainJarVersion();
		File data = updateManager.getMainBlob(version);
		
		if(data == null) {
			Logger.normal(this, "Peer "+source+" asked us for the blob file for the revocation key for the main jar but we don't have it!");
			// Probably a race condition on reconnect, hopefully we'll be asked again
			return true;
		}
		
		final long uid = m.getLong(DMT.UID);
		
		RandomAccessFileWrapper raf; 
		try {
			raf = new RandomAccessFileWrapper(data, "r");
		} catch (FileNotFoundException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the main jar, we have downloaded it but don't have the file even though we did have it when we checked!: "+e, e);
			return true;
		}
		
		final PartiallyReceivedBulk prb;
		long length;
		try {
			length = raf.size();
			prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length, 
					Node.PACKET_SIZE, raf, true);
		} catch (IOException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the main jar, we have downloaded it but we can't determine the file size: "+e, e);
			return true;
		}
		
		final BulkTransmitter bt;
		try {
			bt = new BulkTransmitter(prb, source, uid, updateManager.node.outputThrottle);
		} catch (DisconnectedException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the main jar, then disconnected: "+e, e);
			return true;
		}
		
		final Runnable r = new Runnable() {
			public void run() {
				if(!bt.send()) {
					Logger.error(this, "Failed to send main jar blob to "+source.getPeer()+" : "+source.getName());
				} else {
					Logger.normal(this, "Sent main jar blob to "+source.getPeer()+" : "+source.getName());
				}
			}
			
		};
		
		Message msg = DMT.createUOMSendingMain(uid, length, updateManager.updateURI.toString(), version);
		
		try {
			source.sendAsync(msg, new AsyncMessageCallback() {
				public void acknowledged() {
					if(Logger.shouldLog(Logger.MINOR, this))
						Logger.minor(this, "Sending data...");
					// Send the data
					Thread t = new Thread(r, "Main jar send for "+uid+" to "+source.getPeer()+" : "+source.getName());
					t.setDaemon(true);
					t.start();
				}
				public void disconnected() {
					// Argh
					Logger.error(this, "Peer "+source+" asked us for the blob file for the main jar, then disconnected when we tried to send the UOMSendingMain");				
				}

				public void fatalError() {
					// Argh
					Logger.error(this, "Peer "+source+" asked us for the blob file for the main jar, then got a fatal error when we tried to send the UOMSendingMain");				
				}

				public void sent() {
					if(Logger.shouldLog(Logger.MINOR, this))
						Logger.minor(this, "Message sent, data soon");
				}
				
				public String toString() {
					return super.toString() + "("+uid+":"+source.getPeer()+")";
				}
				
			}, 0, null);
		} catch (NotConnectedException e) {
			Logger.error(this, "Peer "+source+" asked us for the blob file for the main jar, then disconnected when we tried to send the UOMSendingMain: "+e, e);
			return true;
		}
		
		return true;
	}

	public boolean handleSendingMain(Message m, final PeerNode source) {
		final long uid = m.getLong(DMT.UID);
		final long length = m.getLong(DMT.FILE_LENGTH);
		String key = m.getString(DMT.MAIN_JAR_KEY);
		final int version = m.getInt(DMT.MAIN_JAR_VERSION);
		final FreenetURI jarURI;
		try {
			jarURI = new FreenetURI(key).setSuggestedEdition(version);
		} catch (MalformedURLException e) {
			Logger.error(this, "Failed receiving main jar "+version+" because URI not parsable: "+e+" for "+key, e);
			System.err.println("Failed receiving main jar "+version+" because URI not parsable: "+e+" for "+key);
			e.printStackTrace();
			cancelSend(source, uid);
			return true;
		}
		
		if(!jarURI.equals(updateManager.updateURI.setSuggestedEdition(version))) {
			System.err.println("Node sending us a main jar update ("+version+") from the wrong URI:\n"+
					"Node: "+source.getPeer()+" : "+source.getName()+"\n"+
					"Our   URI: "+updateManager.updateURI+"\n"+
					"Their URI: "+jarURI);
			cancelSend(source, uid);
			return true;
		}
		
		if(updateManager.isBlown()) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Key blown, so not receiving main jar from "+source+ "("+uid+")");
			cancelSend(source, uid);
			return true;
		}
		
		if(length > NodeUpdateManager.MAX_MAIN_JAR_LENGTH) {
			System.err.println("Node "+source.getPeer()+" : "+source.getName()+" offered us a main jar ("+version+") "+SizeUtil.formatSize(length)+" long. This is unacceptably long so we have refused the transfer.");
			Logger.error(this, "Node "+source.getPeer()+" : "+source.getName()+" offered us a main jar ("+version+") "+SizeUtil.formatSize(length)+" long. This is unacceptably long so we have refused the transfer.");
			// If the transfer fails, we don't try again.
			cancelSend(source, uid);
			return true;
		}
		
		// Okay, we can receive it
		
		final File temp;
		
		try {
			temp = File.createTempFile("main-", ".fblob.tmp", updateManager.node.getNodeDir());
			temp.deleteOnExit();
		} catch (IOException e) {
			System.err.println("Cannot save new main jar to disk and therefore cannot fetch it from our peer!: "+e);
			e.printStackTrace();
			cancelSend(source, uid);
			return true;
		}
		
		RandomAccessFileWrapper raf; 
		try {
			raf = new RandomAccessFileWrapper(temp, "rw");
		} catch (FileNotFoundException e) {
			Logger.error(this, "Peer "+source+" sending us a main jar binary blob, but we lost the temp file "+temp+" : "+e, e);
			return true;
		}
		
		PartiallyReceivedBulk prb = new PartiallyReceivedBulk(updateManager.node.getUSM(), length, 
				Node.PACKET_SIZE, raf, false);
		
		final BulkReceiver br = new BulkReceiver(prb, source, uid);
		
		Thread t = new Thread(new Runnable() {

			public void run() {
				if(br.receive()) {
					// Success!
					processMainJarBlob(temp, source, version, jarURI);
				} else {
					Logger.error(this, "Failed to transfer main jar "+version+" from "+source);
					System.err.println("Failed to transfer main jar "+version+" from "+source);
				}
			}
			
		}, "Main jar ("+version+") receive for "+uid+" from "+source.getPeer()+" : "+source.getName());
		
		t.setDaemon(true);
		t.start();
		
		return true;
	}

	protected void processMainJarBlob(final File temp, final PeerNode source, final int version, FreenetURI uri) {
		SimpleBlockSet blocks = new SimpleBlockSet();
		
		DataInputStream dis = null;
		try {
			dis = new DataInputStream(new BufferedInputStream(new FileInputStream(temp)));
			BinaryBlob.readBinaryBlob(dis, blocks, true);
		} catch (FileNotFoundException e) {
			Logger.error(this, "Somebody deleted "+temp+" ? We lost the main jar ("+version+") from "+source.userToString()+"!");
			System.err.println("Somebody deleted "+temp+" ? We lost the main jar ("+version+") from "+source.userToString()+"!");
			return;
		} catch (IOException e) {
			Logger.error(this, "Could not read main jar ("+version+") from temp file "+temp+" from node "+source.userToString()+" !");
			System.err.println("Could not read main jar ("+version+") from temp file "+temp+" from node "+source.userToString()+" !");
			// FIXME will be kept until exit for debugging purposes
			return;
		} catch (BinaryBlobFormatException e) {
			Logger.error(this, "Peer "+source.userToString()+" sent us an invalid main jar ("+version+")!: "+e, e);
			System.err.println("Peer "+source.userToString()+" sent us an invalid main jar ("+version+")!: "+e);
			e.printStackTrace();
			// FIXME will be kept until exit for debugging purposes
			return;
		} finally {
			if(dis != null)
				try {
					dis.close();
				} catch (IOException e) {
					// Ignore
				}
		}
		
		// Fetch the jar from the datastore plus the binary blob
		
		FetchContext tempContext = updateManager.node.clientCore.makeClient((short)0, true).getFetchContext();		
		tempContext.localRequestOnly = true;
		tempContext.blocks = blocks;
		
		File f;
		FileBucket b = null;
		try {
			f = File.createTempFile("main-", ".fblob.tmp", updateManager.node.getNodeDir());
			b = new FileBucket(f, false, false, true, true, true);
		} catch (IOException e) {
			Logger.error(this, "Cannot share main jar from "+source.userToString()+" with our peers because cannot write the cleaned version to disk: "+e, e);
			System.err.println("Cannot share main jar from "+source.userToString()+" with our peers because cannot write the cleaned version to disk: "+e);
			e.printStackTrace();
			b = null;
			f = null;
		}
		final FileBucket cleanedBlob = b;
		final File cleanedBlobFile = f;
		
		ClientCallback myCallback = new ClientCallback() {

			public void onFailure(FetchException e, ClientGetter state) {
				if(e.mode == FetchException.CANCELLED) {
					// Eh?
					Logger.error(this, "Cancelled fetch from store/blob of main jar ("+version+") from "+source.userToString());
					System.err.println("Cancelled fetch from store/blob of main jar ("+version+") from "+source.userToString()+" to "+temp+" - please report to developers");
					// Probably best to keep files around for now.
				} else if(e.isFatal()) {
					// Bogus as inserted. Ignore.
					temp.delete();
					Logger.error(this, "Failed to fetch main jar "+version+" from "+source.userToString()+" : fatal error (update was probably inserted badly): "+e, e);
					System.err.println("Failed to fetch main jar "+version+" from "+source.userToString()+" : fatal error (update was probably inserted badly): "+e);
				} else {
					Logger.error(this, "Failed to fetch main jar "+version+" from blob from "+source.userToString());
					System.err.println("Failed to fetch main jar "+version+" from blob from "+source.userToString());
				}
			}

			public void onFailure(InsertException e, BaseClientPutter state) {
				// Ignore, not possible
			}

			public void onFetchable(BaseClientPutter state) {
				// Irrelevant
			}

			public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
				// Ignore, not possible
			}

			public void onMajorProgress() {
				// Ignore
			}

			public void onSuccess(FetchResult result, ClientGetter state) {
				System.err.println("Got main jar version "+version+" from "+source.userToString());
				if(result.size() == 0) {
					System.err.println("Ignoring because 0 bytes long");
					return;
				}
				
				NodeUpdater mainUpdater = updateManager.mainUpdater;
				if(mainUpdater == null) {
					System.err.println("Not updating because updater is disabled!");
					return;
				}
				mainUpdater.onSuccess(result, state, cleanedBlobFile);
				temp.delete();
				insertBlob(mainUpdater.getBlobFile(version));
			}

			public void onSuccess(BaseClientPutter state) {
				// Ignore, not possible
			}
			
		};
		
		ClientGetter cg = new ClientGetter(myCallback, 
				updateManager.node.clientCore.requestStarters.chkFetchScheduler,
				updateManager.node.clientCore.requestStarters.sskFetchScheduler, 
				uri, tempContext, (short)0, this, null, cleanedBlob); 
		
		try {
			cg.start();
		} catch (FetchException e1) {
			myCallback.onFailure(e1, cg);
		}
		
	}
	
}
