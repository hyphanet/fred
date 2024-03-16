package freenet.node.simulator;

import freenet.node.Node;

public class TestUtil {
	static boolean waitForNodes(Node node) throws InterruptedException {
		int targetPeers = node.getOpennet().getAnnouncementThreshold();
		// Wait until we have 10 connected nodes...
		int seconds = 0;
		boolean success = false;
		while (seconds < 600) {
			Thread.sleep(1000);
			int seeds = node.getPeers().countSeednodes();
			int seedConns = node.getPeers().getConnectedSeedServerPeersVector(null).size();
			int opennetPeers = node.getPeers().countValidPeers();
			int opennetConns = node.getPeers().countConnectedOpennetPeers();
			System.err.println("" + seconds + " : seeds: " + seeds + ", connected: " + seedConns + " opennet: peers: "
			        + opennetPeers + ", connected: " + opennetConns);
			seconds++;
			if (opennetConns >= targetPeers) {
				success = true;
				break;
			}
		}
		if (!success)
			System.err.println("Failed to reach target peers count " + targetPeers + " in 10 minutes.");
		return success;
	}
}
