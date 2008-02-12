/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;

import freenet.support.Logger;

/**
 * Track average round-trip time for each peer node, get a geometric mean.
 */
public class NodePinger implements Runnable {

	private double meanPing = 0;
	
	NodePinger(Node n) {
		this.node = n;
	}

	void start() {
		run();
	}
	
	final Node node;
	
	public void run() {
	    //freenet.support.OSThread.RealOSThread.logPID(this);
		try {
			recalculateMean(node.peers.connectedPeers);
		} finally {
			node.ps.queueTimedJob(this, 200);
		}
	}

	/** Recalculate the mean ping time */
	void recalculateMean(PeerNode[] peers) {
		if(peers.length == 0) return;
		meanPing = calculateMedianPing(peers);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Median ping: "+meanPing);
	}
	
	double calculateMedianPing(PeerNode[] peers) {
		
		double[] allPeers = new double[peers.length];
		
		for(int i=0;i<peers.length;i++) {
			PeerNode peer = peers[i];
			double pingTime = peer.averagePingTime();
			allPeers[i] = pingTime;
		}
		
		Arrays.sort(allPeers);
		return allPeers[peers.length / 2];
	}

	public double averagePingTime() {
		return meanPing;
	}
}
