/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;

import freenet.support.Logger;
import freenet.support.math.TimeDecayingRunningAverage;

/**
 * Track average round-trip time for each peer node, get a geometric mean.
 */
public class NodePinger implements Runnable {

	static final double CRAZY_MAX_PING_TIME = 365.25*24*60*60*1000;
	
	private double meanPing = 0;
	/** Average over time to avoid nodes flitting in and out of backoff having too much impact. */
	private TimeDecayingRunningAverage tdra;
	
	NodePinger(Node n) {
		this.node = n;
		this.tdra = new TimeDecayingRunningAverage(0.0, 30*1000, // 30 seconds
				0.0, CRAZY_MAX_PING_TIME);
	}

	void start() {
		Logger.normal(this, "Starting NodePinger");
		Thread t = new Thread(this, "Node pinger");
		t.setDaemon(true);
		t.start();
	}
	
	final Node node;
	
	public void run() {
		while(true) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				// Ignore
			}
			recalculateMean(node.peers.connectedPeers);
		}
	}

	/** Recalculate the mean ping time */
	void recalculateMean(PeerNode[] peers) {
		if(peers.length == 0) return;
		double d = calculateMedianPing(peers);
		tdra.report(d);
		meanPing = tdra.currentValue();
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Reporting ping to temporal averager: "+d+" result "+meanPing);
	}
	
	double calculateMedianPing(PeerNode[] peers) {
		
		double[] allPeers = new double[peers.length];
		
		/** Not backed off peers' ping times */
		double[] nbPeers = new double[peers.length];
		
		/** Number of not backed off peers */
		int nbCount = 0;
		
		for(int i=0;i<peers.length;i++) {
			PeerNode peer = peers[i];
			double pingTime = peer.averagePingTime();
			if(!peer.isRoutingBackedOff()) {
				nbPeers[nbCount++] = pingTime;
			}
			allPeers[i] = pingTime;
		}
		
		if(nbCount > 0) {
			Arrays.sort(nbPeers, 0, nbCount);
			return nbPeers[nbCount / 2]; // round down - prefer lower
		}
		
		Arrays.sort(allPeers);
		return allPeers[peers.length / 2];
	}

	public double averagePingTime() {
		return meanPing;
	}
}
