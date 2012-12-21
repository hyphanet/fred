/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
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
	/** The NodeCrypto with the node's port number and list of peers */
	final NodeCrypto crypto;
	/** ARK inserter. */
	private final NodeARKInserter arkPutter;
	/** Last detected IP address */
	Peer[] lastPeers;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	NodeIPPortDetector(Node node, NodeIPDetector ipDetector, NodeCrypto crypto, boolean enableARKs) {
		this.node = node;
		this.ipDetector = ipDetector;
		this.crypto = crypto;
		arkPutter = new NodeARKInserter(node, crypto, this, enableARKs);
		ipDetector.addPortDetector(this);
	}

	/**
	 * Combine the NodeIPDetector's output with any per-port information we may have to get a definitive
	 * (for that port/NodeCrypto) list of IP addresses (still without port numbers).
	 */
	FreenetInetAddress[] detectPrimaryIPAddress() {
		FreenetInetAddress addr = crypto.getBindTo();
		if(addr.isRealInternetAddress(false, true, false)) {
			// Binding to a real internet address => don't want us to use the others, most likely
			// he is on a multi-homed box where only one IP can be used for Freenet.
			return new FreenetInetAddress[] { addr };
		}
		return ipDetector.detectPrimaryIPAddress(!crypto.config.includeLocalAddressesInNoderefs);
	}

	/**
	 * Get our Peer's. This is a list of IP:port's at which we might be contactable. Some of them
	 * will have the same port as the listenPort, but if we are behind a NAT which rewrites our
	 * port number, some of them may not. (If we're behind a symmetric NAT which rewrites it 
	 * differently for each connection, we're stuffed, and we tell the user).
	 */
	Peer[] detectPrimaryPeers() {
		final boolean logMINOR = NodeIPPortDetector.logMINOR;
		ArrayList<Peer> addresses = new ArrayList<Peer>();
		FreenetInetAddress[] addrs = detectPrimaryIPAddress();
		for(FreenetInetAddress addr: addrs) {
			addresses.add(new Peer(addr, crypto.portNumber));
			if(logMINOR)
				Logger.minor(this, "Adding "+addr);
		}
		// Now try to get the rewritten port number from our peers.
		// Only considering those within this crypto port, this time.
		
		PeerNode[] peerList = crypto.getPeerNodes();
		
		if(peerList != null) {
			HashMap<Peer,Integer> countsByPeer = new HashMap<Peer,Integer>();
			// FIXME use a standard mutable int object, we have one somewhere
			for(PeerNode pn: peerList) {
				Peer p = pn.getRemoteDetectedPeer();
				if((p == null) || p.isNull()) continue;
				// DNSRequester doesn't deal with our own node
				if(!IPUtil.isValidAddress(p.getAddress(true), false)) continue;
				if(logMINOR)
					Logger.minor(this, "Peer "+pn.getPeer()+" thinks we are "+p);
				if(countsByPeer.containsKey(p)) {
					countsByPeer.put(p, countsByPeer.get(p) + 1);
				} else {
					countsByPeer.put(p, 1);
				}
			}
			if(countsByPeer.size() == 1) {
				Iterator<Peer> it = countsByPeer.keySet().iterator();
				Peer p = (it.next());
				Logger.minor(this, "Everyone agrees we are "+p);
				if(!addresses.contains(p)) {
					addresses.add(p);
				}
			} else if(countsByPeer.size() > 1) {
				// Take two most popular addresses.
				Peer best = null;
				Peer secondBest = null;
				int bestPopularity = 0;
				int secondBestPopularity = 0;
				for (Map.Entry<Peer,Integer> entry : countsByPeer.entrySet()) {
					Peer cur = entry.getKey();
					int curPop = entry.getValue();
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
		lastPeers = addresses.toArray(new Peer[addresses.size()]);
		if(logMINOR)
			Logger.minor(this, "Returning for port "+crypto.portNumber+" : "+Arrays.toString(lastPeers));
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
		for(FreenetInetAddress ai: a)
			if(ai.equals(addr)) return true;
		return false;
	}
}
