/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * @author amphibian
 * 
 * Thread that does DNS queries for unconnected peers
 */
public class DNSRequester implements Runnable {

    final Node node;
    private long lastLogTime;
    private final Set<Double> recentNodeIdentitySet = new HashSet<>();
    private final Deque<Double> recentNodeIdentityQueue = new ArrayDeque<>();
    // Only set when doing simulations.
    static boolean DISABLE = false;


    private static volatile boolean logMINOR;
    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

    DNSRequester(Node node) {
        this.node = node;
    }

    void start() {
    	Logger.normal(this, "Starting DNSRequester");
    	System.out.println("Starting DNSRequester");
    	node.getExecutor().execute(this, "DNSRequester thread for "+node.getDarknetPortNumber());
    }

    @Override
    public void run() {
        while(true) {
            try {
                realRun();
            } catch (Throwable t) {
                Logger.error(this, "Caught in DNSRequester: "+t, t);
            }
        }
    }

    private void realRun() {
        // run DNS requests for not recently checked, unconnected
        // nodes to avoid the coupon collector's problem
        PeerNode[] nodesToCheck = Arrays.stream(node.getPeers().myPeers())
            .filter(peerNode -> !peerNode.isConnected())
            // identify recent nodes by location, because the exact location cannot be used twice
            // (that already triggers the simplest pitch black attack defenses)
            // Double may not be comparable in general (floating point),
            // but just checking for equality with itself is safe
            .filter(peerNode -> !recentNodeIdentitySet.contains(peerNode.getLocation()))
            .toArray(PeerNode[]::new);

        if(logMINOR) {
            long now = System.currentTimeMillis();
            if((now - lastLogTime) > 100) {
            		Logger.minor(this, "Processing DNS Requests (log rate-limited)");
            }
            lastLogTime = now;
        }

        int unconnectedNodesLength = nodesToCheck.length;
        if (unconnectedNodesLength == 0) {
            return; // nothing to do
        }
        // check a randomly chosen node that has not been checked
        // recently to avoid sending bursts of DNS requests
        PeerNode pn = nodesToCheck[node.getFastWeakRandom().nextInt(unconnectedNodesLength)];
        if (unconnectedNodesLength < 5) {
            // no need for optimizations: just clear all state
            recentNodeIdentitySet.clear();
            recentNodeIdentityQueue.clear();
        } else {
            // do not request this node again,
            // until at least 81% of the other unconnected nodes have been checked
            recentNodeIdentitySet.add(pn.getLocation());
            recentNodeIdentityQueue.offerFirst(pn.getLocation());
            while (recentNodeIdentityQueue.size() > (0.81 * unconnectedNodesLength)) {
                recentNodeIdentitySet.remove(recentNodeIdentityQueue.removeLast());
            }
        }

        // Try new DNS lookup
        pn.maybeUpdateHandshakeIPs(false);

        int nextMaxWaitTime = 1000 + node.getFastWeakRandom().nextInt(60000);
        try {
            synchronized(this) {
                wait(nextMaxWaitTime);  // sleep 1-61s ...
            }
        } catch (InterruptedException e) {
            // Ignore, just wake up. Just sleeping to not busy wait anyway
        }
    }

	public void forceRun() {
		synchronized(this) {
			notifyAll();
		}
	}
}
