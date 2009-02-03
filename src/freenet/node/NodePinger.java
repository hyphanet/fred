/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;

import freenet.support.Logger;
import freenet.support.LogThresholdCallback;

/**
 * Track average round-trip time for each peer node, get a geometric mean.
 */
public class NodePinger implements Runnable {
    private static volatile boolean logMINOR;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(Logger.MINOR, this);
            }
        });
    }

	private final Node node;
	private volatile double meanPing = 0;
	
	public static final double CRAZY_MAX_PING_TIME = 365.25*24*60*60*1000;
	
	NodePinger(Node n) {
		this.node = n;
	}

	void start() {
		run();
	}
	
	public void run() {
        // Requeue *before* so that it's accurate in any case
        node.ps.queueTimedJob(this, 200);
        
        PeerNode[] peers = null;
        synchronized(node.peers) {
            System.arraycopy(node.peers.connectedPeers, 0, peers, 0, node.peers.connectedPeers.length);
        }

        // Now we don't have to care about synchronization anymore
        recalculateMean(peers);
	}

	/** Recalculate the mean ping time */
	private void recalculateMean(PeerNode[] peers) {
		if(peers.length == 0) return;
		meanPing = calculateMedianPing(peers);
		if(logMINOR)
			Logger.minor(this, "Median ping: "+meanPing);
	}
	
	private double calculateMedianPing(PeerNode[] peers) {
		double[] allPeers = new double[peers.length];
        for(int i = 0; i < peers.length; i++) {
            PeerNode peer = peers[i];
            allPeers[i] = peer.averagePingTime();
        }
		
		Arrays.sort(allPeers);
		return allPeers[peers.length / 2];
	}

	public double averagePingTime() {
		return meanPing;
	}
}