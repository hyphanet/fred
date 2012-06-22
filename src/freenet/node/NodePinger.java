/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;

import freenet.node.NodeStats.PeerLoadStats;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Track average round-trip time for each peer node, get a geometric mean.
 */
public class NodePinger implements Runnable {
    private static volatile boolean logMINOR;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
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
	
	@Override
	public void run() {
        try {
        PeerNode[] peers = null;
        synchronized(node.peers) {
        	peers = node.peers.connectedPeers();
        }
        if(peers == null || peers.length == 0) return;

        // Now we don't have to care about synchronization anymore
        recalculateMean(peers);
        capacityInputRealtime.calculate(peers);
        capacityInputBulk.calculate(peers);
        capacityOutputRealtime.calculate(peers);
        capacityOutputBulk.calculate(peers);
        } finally {
        	// Requeue after to avoid exacerbating overload
        	node.getTicker().queueTimedJob(this, 200);
        }
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
	
	final CapacityChecker capacityInputRealtime = new CapacityChecker(true, true);
	final CapacityChecker capacityInputBulk = new CapacityChecker(true, false);
	final CapacityChecker capacityOutputRealtime = new CapacityChecker(false, true);
	final CapacityChecker capacityOutputBulk = new CapacityChecker(false, false);
	
	class CapacityChecker {
		final boolean isInput;
		final boolean isRealtime;
		private double min;
		private double median;
		private double firstQuartile;
		private double lastQuartile;
		private double max;
		
		CapacityChecker(boolean input, boolean realtime) {
			isInput = input;
			isRealtime = realtime;
		}
		
		void calculate(PeerNode[] peers) {
			double[] allPeers = new double[peers.length];
			int x = 0;
			for(PeerNode peer : peers) {
				PeerLoadStats stats = peer.outputLoadTracker(isRealtime).getLastIncomingLoadStats();
				if(stats == null) continue;
				allPeers[x++] = stats.peerLimit(isInput);
			}
			if(x != peers.length) {
				double[] newPeers = new double[x];
				System.arraycopy(allPeers, 0, newPeers, 0, x);
				allPeers = newPeers;
			}
			Arrays.sort(allPeers);
			if(x == 0) return;
			synchronized(this) {
				min = allPeers[0];
				median = allPeers[x / 2];
				firstQuartile = allPeers[x / 4];
				lastQuartile = allPeers[(x * 3) / 4];
				max = allPeers[x - 1];
				if(logMINOR) Logger.minor(this, "Quartiles for peer capacities: "+(isInput?"input ":"output ")+(isRealtime?"realtime: ":"bulk: ")+Arrays.toString(getQuartiles()));
			}
		}
		
		synchronized double[] getQuartiles() {
			return new double[] { min, firstQuartile, median, lastQuartile, max };
		}
		
		/** Get min(half the median, first quartile). Used as a threshold. */
		synchronized double getThreshold() {
			return Math.min(median/2, firstQuartile);
		}
	}

	public double capacityThreshold(boolean isRealtime, boolean isInput) {
		return capacityChecker(isRealtime, isInput).getThreshold();
	}

	private CapacityChecker capacityChecker(boolean isRealtime, boolean isInput) {
		if(isRealtime) {
			return isInput ? capacityInputRealtime : capacityOutputRealtime;
		} else {
			return isInput ? capacityInputBulk: capacityOutputBulk;
		}
	}
}
