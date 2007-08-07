/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Vector;

import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Central location for all things opennet.
 * In particular:
 * - Opennet crypto
 * - LRU connections
 * @author toad
 */
public class OpennetManager {
	
	final Node node;
	final NodeCrypto crypto;
	
	/** Our peers. PeerNode's are promoted when they successfully fetch a key. Normally we take
	 * the bottom peer, but if that isn't eligible to be dropped, we iterate up the list. */
	private final LRUQueue peersLRU;
	/** Time at which last dropped a peer */
	private long timeLastDropped;
	/** Number of successful CHK requests since last added a node */
	private long successCount;
	
	/** Only drop a connection after at least this many successful requests */
	// FIXME should be a function of # opennet peers? max # opennet peers? ...
	static final int MIN_SUCCESS_BETWEEN_DROP_CONNS = 10;
	// FIXME make this configurable
	static final int MAX_PEERS = 15;
	/** Chance of resetting path folding (for plausible deniability) is 1 in this number. */
	static final int RESET_PATH_FOLDING_PROB = 20;
	/** Don't re-add a node until it's been up and disconnected for at least this long */
	static final int DONT_READD_TIME = 60*1000;
	/** Don't drop a node until it's at least this old */
	static final int DROP_MIN_AGE = 300*1000;
	/** Don't drop a node until this long after startup */
	static final int DROP_STARTUP_DELAY = 300*1000;
	/** Don't drop a node until this long after losing connection to it */
	static final int DROP_DISCONNECT_DELAY = 300*1000;
	/** But if it has disconnected more than once in this period, allow it to be dropped anyway */
	static final int DROP_DISCONNECT_DELAY_COOLDOWN = 60*60*1000;
	/** Every DROP_CONNECTED_TIME, we may drop a peer even though it is connected */
	static final int DROP_CONNECTED_TIME = 10*60*1000;
	/** Minimum time between offers, if we have maximum peers. Less than the above limits,
	 * since an offer may not be accepted. */
	static final int MIN_TIME_BETWEEN_OFFERS = 30*1000;
	private static boolean logMINOR;

	public OpennetManager(Node node, NodeCryptoConfig opennetConfig) throws NodeInitException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.node = node;
		crypto =
			new NodeCrypto(node, true, opennetConfig);

		File nodeFile = new File(node.nodeDir, "opennet-"+crypto.portNumber);
		File backupNodeFile = new File("opennet-"+crypto.portNumber+".bak");
		
		// Keep opennet crypto details in a separate file
		try {
			readFile(nodeFile);
		} catch (IOException e) {
			try {
				readFile(backupNodeFile);
			} catch (IOException e1) {
				crypto.initCrypto();
			}
		}
		peersLRU = new LRUQueue();
		node.peers.tryReadPeers(new File(node.nodeDir, "openpeers-"+crypto.portNumber).toString(), crypto, this, true);
		OpennetPeerNode[] nodes = node.peers.getOpennetPeers();
		Arrays.sort(nodes, new Comparator() {
			public int compare(Object arg0, Object arg1) {
				OpennetPeerNode pn1 = (OpennetPeerNode) arg0;
				OpennetPeerNode pn2 = (OpennetPeerNode) arg1;
				
				long lastSuccess1 = pn1.timeLastSuccess();
				long lastSuccess2 = pn2.timeLastSuccess();
				
				if(lastSuccess1 > lastSuccess2) return 1;
				if(lastSuccess2 > lastSuccess1) return -1;
				
				boolean neverConnected1 = pn1.neverConnected();
				boolean neverConnected2 = pn2.neverConnected();
				if(neverConnected1 && (!neverConnected2))
					return -1;
				if((!neverConnected1) && neverConnected2)
					return 1;
				return pn1.hashCode - pn2.hashCode;
			}
		});
		for(int i=0;i<nodes.length;i++)
			peersLRU.push(nodes[i]);
		dropExcessPeers();
		writeFile(nodeFile, backupNodeFile);
	}

	private void writeFile(File orig, File backup) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		SimpleFieldSet fs = crypto.exportPrivateFieldSet();
		
		if(orig.exists()) backup.delete();
		
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(backup);
			OutputStreamWriter osr = new OutputStreamWriter(fos, "UTF-8");
			BufferedWriter bw = new BufferedWriter(osr);
			fs.writeTo(bw);
			bw.close();
			if(!backup.renameTo(orig)) {
				orig.delete();
				if(!backup.renameTo(orig)) {
					Logger.error(this, "Could not rename new node file "+backup+" to "+orig);
				}
			}
		} catch (IOException e) {
			if(fos != null) {
				try {
					fos.close();
				} catch (IOException e1) {
					Logger.error(this, "Cannot close "+backup+": "+e1, e1);
				}
			}
		}
	}

	private void readFile(File filename) throws IOException {
		// REDFLAG: Any way to share this code with Node and NodePeer?
		FileInputStream fis = new FileInputStream(filename);
		InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
		BufferedReader br = new BufferedReader(isr);
		SimpleFieldSet fs = new SimpleFieldSet(br, false, true);
		br.close();
		// Read contents
		String[] udp = fs.getAll("physical.udp");
		if((udp != null) && (udp.length > 0)) {
			for(int i=0;i<udp.length;i++) {
				// Just keep the first one with the correct port number.
				Peer p;
				try {
					p = new Peer(udp[i], false);
				} catch (PeerParseException e) {
					IOException e1 = new IOException();
					e1.initCause(e);
					throw e1;
				}
				if(p.getPort() == crypto.portNumber) {
					// DNSRequester doesn't deal with our own node
					node.ipDetector.setOldIPAddress(p.getFreenetAddress());
					break;
				}
			}
		}
		
		crypto.readCrypto(fs);
	}

	public void start() {
		crypto.start(node.disableHangCheckers);
	}

	/**
	 * Called when opennet is disabled
	 */
	public void stop() {
		crypto.stop();
		node.peers.removeOpennetPeers();
	}

	public boolean addNewOpennetNode(SimpleFieldSet fs) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		OpennetPeerNode pn = new OpennetPeerNode(fs, node, crypto, this, node.peers, false, crypto.packetMangler);
		if(Arrays.equals(pn.getIdentity(), crypto.myIdentity)) {
			if(logMINOR) Logger.minor(this, "Not adding self as opennet peer");
			return false; // Equal to myself
		}
		PeerNode match;
		if(((match = node.peers.containsPeer(pn)) != null) && 
				(match.isConnected() || (!match.neverConnected()) || 
						match.timeSinceAddedOrRestarted() < DONT_READD_TIME)) {
			if(logMINOR) Logger.minor(this, "Not adding "+pn.userToString()+" to opennet list as already there");
			return false;
		}
		return wantPeer(pn, true); 
		// Start at bottom. Node must prove itself.
	}

	/** When did we last offer our noderef to some other node? */
	private long timeLastOffered;
	
	/**
	 * Trim the peers list and possibly add a new node. Note that if we are not adding a new node,
	 * we will only return true every MIN_TIME_BETWEEN_OFFERS, to prevent problems caused by many
	 * pending offers being accepted simultaneously.
	 * @param nodeToAddNow Node to add.
	 * @param addAtLRU If there is a node to add, add it at the bottom rather than the top. Normally
	 * we set this on new path folded nodes so that they will be replaced if during the trial period,
	 * plus the time it takes to get a new path folding offer, they don't have a successful request.
	 * @return True if the node was added / should be added.
	 */
	public boolean wantPeer(PeerNode nodeToAddNow, boolean addAtLRU) {
		boolean ret = true;
		boolean noDisconnect;
		synchronized(this) {
			if(peersLRU.size() < MAX_PEERS) {
				if(nodeToAddNow != null) {
					if(logMINOR) Logger.minor(this, "Added opennet peer "+nodeToAddNow+" as opennet peers list not full");
					peersLRU.push(nodeToAddNow);
					// Always take OpennetManager lock before PeerManager
					node.peers.addPeer(nodeToAddNow);
				} else {
					if(logMINOR) Logger.minor(this, "Want peer because not enough opennet nodes");
				}
				timeLastOffered = System.currentTimeMillis();
				return true;
			}
			noDisconnect = successCount < MIN_SUCCESS_BETWEEN_DROP_CONNS;
		}
		Vector dropList = new Vector();
		synchronized(this) {
			boolean hasDisconnected = false;
			while(peersLRU.size() > MAX_PEERS - (nodeToAddNow == null ? 0 : 1)) {
				PeerNode toDrop;
				// can drop peers which are over the limit
				toDrop = peerToDrop(noDisconnect && nodeToAddNow != null && peersLRU.size() == MAX_PEERS, true);
				if(toDrop == null) {
					if(logMINOR)
						Logger.minor(this, "No more peers to drop, cannot accept peer"+(nodeToAddNow == null ? "" : nodeToAddNow.toString()));
					ret = false;
					break;
				}
				if(logMINOR)
					Logger.minor(this, "Drop opennet peer: "+toDrop+" (connected="+toDrop.isConnected()+")");
				if(!toDrop.isConnected())
					hasDisconnected = true;
				peersLRU.remove(toDrop);
				dropList.add(toDrop);
			}
			if(ret) {
				long now = System.currentTimeMillis();
				if(nodeToAddNow != null) {
					if(!node.peers.addPeer(nodeToAddNow)) {
						// Can't add it, already present (some sort of race condition)
						PeerNode readd = (PeerNode) dropList.remove(dropList.size()-1);
						peersLRU.pushLeast(readd);
						ret = false;
						if(logMINOR) Logger.minor(this, "Could not add opennet peer "+nodeToAddNow+" because already in list");
					} else {
						if(addAtLRU)
							peersLRU.pushLeast(nodeToAddNow);
						else
							peersLRU.push(nodeToAddNow);
						if(logMINOR) Logger.minor(this, "Added opennet peer "+nodeToAddNow+" after clearing "+dropList.size()+" items");					
					}
					if(!dropList.isEmpty())
						timeLastDropped = now;
				} else {
					if(now - timeLastOffered <= MIN_TIME_BETWEEN_OFFERS && !hasDisconnected) {
						if(logMINOR)
							Logger.minor(this, "Cannot accept peer because of minimum time between offers (last offered "+(now-timeLastOffered)+" ms ago)");
						// Cancel
						ret = false;
					} else {
						if(!dropList.isEmpty())
							timeLastDropped = now;
						timeLastOffered = now;
					}
				}
			}
		}
		for(int i=0;i<dropList.size();i++) {
			OpennetPeerNode pn = (OpennetPeerNode) dropList.get(i);
			if(logMINOR) Logger.minor(this, "Dropping LRU opennet peer: "+pn);
			node.peers.disconnect(pn);
		}
		return ret;
	}

	private void dropExcessPeers() {
		while(peersLRU.size() > MAX_PEERS) {
			PeerNode toDrop;
			toDrop = peerToDrop(false, false);
			if(toDrop == null) return;
			peersLRU.remove(toDrop);
			node.peers.disconnect(toDrop);
		}
	}
	
	synchronized PeerNode peerToDrop(boolean noDisconnect, boolean resetSuccessCount) {
		if(peersLRU.size() < MAX_PEERS) {
			// Don't drop any peers
			return null;
		} else {
			// Do we want it?
			OpennetPeerNode[] peers = (OpennetPeerNode[]) peersLRU.toArrayOrdered(new OpennetPeerNode[peersLRU.size()]);
			for(int i=0;i<peers.length;i++) {
				OpennetPeerNode pn = peers[i];
				if(pn == null) continue;
				if(!pn.isDroppable()) continue;
				// LOCKING: Always take the OpennetManager lock first
				if(!pn.isConnected()) {
					if(Logger.shouldLog(Logger.MINOR, this))
						Logger.minor(this, "Possibly dropping opennet peer "+pn+" as is disconnected");
					return pn;
				}
			}
			if(System.currentTimeMillis() - timeLastDropped < DROP_CONNECTED_TIME)
				return null;
			if(noDisconnect) return null;
			for(int i=0;i<peers.length;i++) {
				OpennetPeerNode pn = peers[i];
				if(pn == null) continue;
				if(!pn.isDroppable()) continue;
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Possibly dropping opennet peer "+pn+" "+
							(System.currentTimeMillis() - timeLastDropped)+" ms since last dropped peer");
				if(resetSuccessCount)
					successCount = 0;
				return pn;
			}
		}
		return null;
	}

	public void onSuccess(OpennetPeerNode pn) {
		synchronized(this) {
			successCount++;
			if(peersLRU.contains(pn)) {
				peersLRU.push(pn);
				Logger.normal(this, "Opennet peer "+pn+" promoted to top of LRU because of successful request");
				return;
			} else {
				if(logMINOR) Logger.minor(this, "Success on opennet peer which isn't in the LRU!: "+pn, new Exception("debug"));
				// Re-add it: nasty race condition when we have few peers
			}
		}
		if(!wantPeer(pn, false)) // Start at top as it just succeeded
			node.peers.disconnect(pn);
	}

	public synchronized void onRemove(OpennetPeerNode pn) {
		peersLRU.remove(pn);
	}

}
