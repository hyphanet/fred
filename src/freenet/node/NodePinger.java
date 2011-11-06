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
	    if((node.peers.connectedPeers == null) || (node.peers.connectedPeers.length == 0)) return;
	    peers = new PeerNode[node.peers.connectedPeers.length];
            System.arraycopy(node.peers.connectedPeers, 0, peers, 0, node.peers.connectedPeers.length);
        }

        // Now we don't have to care about synchronization anymore
        recalculateMean(peers);
        capacityInputRealtimeSSK.calculate(peers);
        capacityInputRealtimeCHK.calculate(peers);
        capacityInputBulkSSK.calculate(peers);
        capacityInputBulkCHK.calculate(peers);
        capacityOutputRealtimeSSK.calculate(peers);
        capacityOutputRealtimeCHK.calculate(peers);
        capacityOutputBulkSSK.calculate(peers);
        capacityOutputBulkCHK.calculate(peers);
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
	
	final CapacityChecker capacityInputRealtimeCHK = new CapacityChecker(true, true, false);
	final CapacityChecker capacityInputRealtimeSSK = new CapacityChecker(true, true, true);
	final CapacityChecker capacityInputBulkCHK = new CapacityChecker(true, false, false);
	final CapacityChecker capacityInputBulkSSK = new CapacityChecker(true, false, true);
	final CapacityChecker capacityOutputRealtimeCHK = new CapacityChecker(false, true, false);
	final CapacityChecker capacityOutputRealtimeSSK = new CapacityChecker(false, true, true);
	final CapacityChecker capacityOutputBulkCHK = new CapacityChecker(false, false, false);
	final CapacityChecker capacityOutputBulkSSK = new CapacityChecker(false, false, true);
	
	class CapacityChecker {
		final boolean isInput;
		final boolean isRealtime;
		final boolean isSSK;
		private double min;
		private double median;
		private double firstQuartile;
		private double lastQuartile;
		private double max;
		
		CapacityChecker(boolean input, boolean realtime, boolean ssk) {
			isInput = input;
			isRealtime = realtime;
			isSSK = ssk;
		}
		
		void calculate(PeerNode[] peers) {
			double[] allPeers = new double[peers.length];
			int x = 0;
			for(int i = 0; i < peers.length; i++) {
				PeerNode peer = peers[i];
				PeerLoadStats stats = peer.outputLoadTracker(isRealtime, isSSK).getLastIncomingLoadStats();
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

	public double capacityThreshold(boolean isRealtime, boolean isInput, boolean isSSK) {
		return capacityChecker(isRealtime, isInput, isSSK).getThreshold();
	}

	private CapacityChecker capacityChecker(boolean isRealtime, boolean isInput, boolean isSSK) {
		if(isSSK) {
			if(isRealtime) {
				return isInput ? capacityInputRealtimeSSK : capacityOutputRealtimeSSK;
			} else {
				return isInput ? capacityInputBulkSSK : capacityOutputBulkSSK;
			}
		} else {
			if(isRealtime) {
				return isInput ? capacityInputRealtimeCHK : capacityOutputRealtimeCHK;
			} else {
				return isInput ? capacityInputBulkCHK : capacityOutputBulkCHK;
			}
		}
	}
}
