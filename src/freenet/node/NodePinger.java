package freenet.node;

import freenet.support.Logger;

public class NodePinger implements Runnable {

	private double meanPing = 0;
	
	NodePinger(Node n) {
		this.node = n;
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
			PeerNode[] peers = node.peers.connectedPeers;
			if(peers == null) continue;
			recalculateMean(peers);
			for(int i=0;i<peers.length;i++) {
				PeerNode pn = peers[i];
				if(!pn.isConnected())
					continue;
				pn.sendPing();
				recalculateMean(peers);
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}

	/** Recalculate the mean ping time */
	private void recalculateMean(PeerNode[] peers) {
		int peerCount = 0;
		double total = 1.0;
		for(int i=0;i<peers.length;i++) {
			PeerNode peer = peers[i];
			if(!peer.isConnected()) continue;
			peerCount++;
			double avgPingTime = peer.averagePingTime();
			double avgThrottledPacketSendTime = peer.throttledPacketSendAverage.currentValue();
			double value = Math.max(avgPingTime, avgThrottledPacketSendTime);
			total *= value;
		}
		if(peerCount > 0) {
			total = Math.pow(total, 1.0 / peerCount);
			meanPing = total;
			Logger.minor(this, "Mean ping: "+meanPing+"ms");
		}
	}

	public double averagePingTime() {
		return meanPing;
	}
}
