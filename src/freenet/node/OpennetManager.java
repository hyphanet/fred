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
	
	// FIXME make this configurable
	static final int MAX_PEERS = 30;
	/** Chance of resetting path folding (for plausible deniability) is 1 in this number. */
	static final int RESET_PATH_FOLDING_PROB = 20;
	/** Don't re-add a node until it's been up and disconnected for at least this long */
	static final int DONT_READD_TIME = 60*1000;
	/** Don't drop a node until it's at least this old */
	static final int DROP_ELIGIBLE_TIME = 300*1000;

	public OpennetManager(Node node, NodeCryptoConfig opennetConfig) throws NodeInitException {
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
		writeFile(nodeFile, backupNodeFile);
	}

	private void writeFile(File orig, File backup) {
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
			Logger.error(this, "Not adding self as opennet peer");
			return false; // Equal to myself
		}
		PeerNode match;
		if(((match = node.peers.containsPeer(pn)) != null) && 
				(match.isConnected() || (!match.neverConnected()) || 
						match.timeSinceAddedOrRestarted() < DONT_READD_TIME)) {
			Logger.error(this, "Not adding "+pn.userToString()+" to opennet list as already there");
			return false;
		}
		if(!wantPeer()) {
			Logger.error(this, "Not adding "+pn.userToString()+" to opennet list as don't want it");
			return false;
		}
		return node.peers.addPeer(pn); // False = already in peers list
	}

	public boolean wantPeer() {
		// FIXME implement LRU !!!
		if(node.peers.getOpennetPeers().length >= MAX_PEERS) return false;
		return true;
	}

}
