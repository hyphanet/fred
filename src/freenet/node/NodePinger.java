/*
  NodePinger.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.node;

import java.util.Arrays;

import freenet.support.Logger;
import freenet.support.math.TimeDecayingRunningAverage;

/**
 * Track average round-trip time for each peer node, get a geometric mean.
 */
public class NodePinger implements Runnable {

	private double meanPing = 0;
	/** Average over time to avoid nodes flitting in and out of backoff having too much impact. */
	private TimeDecayingRunningAverage tdra;
	
	NodePinger(Node n) {
		this.node = n;
		this.tdra = new TimeDecayingRunningAverage(0.0, 30*1000, // 30 seconds
				0.0, Double.MAX_VALUE);
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
