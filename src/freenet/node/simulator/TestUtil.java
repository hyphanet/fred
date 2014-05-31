/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.simulator;

//~--- non-JDK imports --------------------------------------------------------

import freenet.node.Node;

public class TestUtil {
    static boolean waitForNodes(Node node) throws InterruptedException {
        int targetPeers = node.getOpennet().getAnnouncementThreshold();

        // Wait until we have 10 connected nodes...
        int seconds = 0;
        boolean success = false;

        while (seconds < 600) {
            Thread.sleep(1000);

            int seeds = node.peers.countSeednodes();
            int seedConns = node.peers.getConnectedSeedServerPeersVector(null).size();
            int opennetPeers = node.peers.countValidPeers();
            int opennetConns = node.peers.countConnectedOpennetPeers();

            System.err.println("" + seconds + " : seeds: " + seeds + ", connected: " + seedConns + " opennet: peers: "
                               + opennetPeers + ", connected: " + opennetConns);
            seconds++;

            if (opennetConns >= targetPeers) {
                success = true;

                break;
            }
        }

        if (!success) {
            System.err.println("Failed to reach target peers count " + targetPeers + " in 10 minutes.");
        }

        return success;
    }
}
