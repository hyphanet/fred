/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.support.Logger;
import freenet.support.transport.ip.IPUtil;

/**
 * Combine the detected IP address with the NodeCrypto's port number and the port numbers we have
 * on connections in the given class to get a list of Peer's.
 * @author toad
 */
public class NodeIPPortDetector {

	/** The Node object */
	final Node node;
	/** The NodeIPDetector which determines the node's IP address but not its port number */
	final NodeIPDetector ipDetector;
	/** The NodeCrypto with the node's port number */
	final NodeCrypto crypto;
	/** ARK inserter. */
	private final NodeARKInserter arkPutter;
	/** Last detected IP address */
	Peer[] lastPeers;
	
	NodeIPPortDetector(Node node, NodeIPDetector ipDetector, NodeCrypto crypto) {
		this.node = node;
		this.ipDetector = ipDetector;
		this.crypto = crypto;
		arkPutter = new NodeARKInserter(node, crypto, this);
	}

	/**
	 * Combine the NodeIPDetector's output with any per-port information we may have to get a definitive
	 * (for that port/NodeCrypto) list of IP addresses (still without port numbers).
	 */
	FreenetInetAddress[] detectPrimaryIPAddress() {
		FreenetInetAddress[] addresses = ipDetector.detectPrimaryIPAddress();
		FreenetInetAddress addr = crypto.bindto;
		if(addr.isRealInternetAddress(false, true)) {
			for(int i=0;i<addresses.length;i++) {
				if(addresses[i] == addr) return addresses;
			}
			FreenetInetAddress[] newAddresses = new FreenetInetAddress[addresses.length+1];
			System.arraycopy(addresses, 0, newAddresses, 0, addresses.length);
			return newAddresses;
		}
		return addresses;
	}

	Peer[] detectPrimaryPeers() {
		Vector addresses = new Vector();
		FreenetInetAddress[] addrs = detectPrimaryIPAddress();
		for(int i=0;i<addrs.length;i++) {
			addresses.add(new Peer(addrs[i], crypto.portNumber));
		}
		// Now try to get the rewritten port number from our peers.
		// Only considering those within this crypto port, this time.
		
		PeerNode[] peerList = crypto.getPeerNodes();
		
		if(peerList != null) {
			HashMap countsByPeer = new HashMap();
			// FIXME use a standard mutable int object, we have one somewhere
			for(int i=0;i<peerList.length;i++) {
				Peer p = peerList[i].getRemoteDetectedPeer();
				if((p == null) || p.isNull()) continue;
				// DNSRequester doesn't deal with our own node
				if(!IPUtil.isValidAddress(p.getAddress(true), false)) continue;
				Logger.normal(this, "Peer "+peerList[i].getPeer()+" thinks we are "+p);
				if(countsByPeer.containsKey(p)) {
					Integer count = (Integer) countsByPeer.get(p);
					Integer newCount = new Integer(count.intValue()+1);
					countsByPeer.put(p, newCount);
				} else {
					countsByPeer.put(p, new Integer(1));
				}
			}
			if(countsByPeer.size() == 1) {
				Iterator it = countsByPeer.keySet().iterator();
				Peer p = (Peer) (it.next());
				Logger.minor(this, "Everyone agrees we are "+p);
				if(!addresses.contains(p)) {
					addresses.add(p);
				}
			} else if(countsByPeer.size() > 1) {
				Iterator it = countsByPeer.keySet().iterator();
				// Take two most popular addresses.
				Peer best = null;
				Peer secondBest = null;
				int bestPopularity = 0;
				int secondBestPopularity = 0;
				while(it.hasNext()) {
					Peer cur = (Peer) (it.next());
					int curPop = ((Integer) (countsByPeer.get(cur))).intValue();
					Logger.normal(this, "Detected peer: "+cur+" popularity "+curPop);
					if(curPop >= bestPopularity) {
						secondBestPopularity = bestPopularity;
						bestPopularity = curPop;
						secondBest = best;
						best = cur;
					}
				}
				if(best != null) {
					if((bestPopularity > 1) || (addrs.length == 0)) {
 						if(!addresses.contains(best)) {
							Logger.normal(this, "Adding best peer "+best+" ("+bestPopularity+ ')');
							addresses.add(best);
						}
						if((secondBest != null) && (secondBestPopularity > 1)) {
							if(!addresses.contains(secondBest)) {
								Logger.normal(this, "Adding second best peer "+secondBest+" ("+secondBest+ ')');
								addresses.add(secondBest);
							}
							if(best.getAddress().equals(secondBest.getAddress()) && bestPopularity == 1) {
								Logger.error(this, "Hrrrm, maybe this is a symmetric NAT? Expect trouble connecting!");
								System.err.println("Hrrrm, maybe this is a symmetric NAT? Expect trouble connecting!");
								
								ipDetector.setMaybeSymmetric();
								
								Peer p = new Peer(best.getFreenetAddress(), crypto.portNumber);
								if(!addresses.contains(p))
									addresses.add(p);

							}
						}
					}
				}
			}
		}
		lastPeers = (Peer[]) addresses.toArray(new Peer[addresses.size()]);
		return lastPeers;
	}
	
	void update() {
		arkPutter.update();
	}

	void startARK() {
		arkPutter.start();
	}

	public Peer[] getPrimaryPeers() {
		if(lastPeers == null)
			return detectPrimaryPeers();
		else
			return lastPeers;
	}

	public boolean includes(FreenetInetAddress addr) {
		FreenetInetAddress[] a = detectPrimaryIPAddress();
		for(int i=0;i<a.length;i++)
			if(a[i].equals(addr)) return true;
		return false;
	}
}
