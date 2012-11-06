/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import freenet.crypt.RandomSource;
import freenet.io.comm.PeerParseException;
import freenet.node.FSParseException;
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStats;
import freenet.node.PeerNode;
import freenet.node.DarknetPeerNode.FRIEND_TRUST;
import freenet.node.DarknetPeerNode.FRIEND_VISIBILITY;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Optional base class for RealNode*Test.
 * Has some useful utilities.
 * @author toad
 * @author robert
 */
public class RealNodeTest {

	static final int EXIT_BASE = NodeInitException.EXIT_NODE_UPPER_LIMIT;
	static final int EXIT_CANNOT_DELETE_OLD_DATA = EXIT_BASE + 3;
	static final int EXIT_PING_TARGET_NOT_REACHED = EXIT_BASE + 4;
	static final int EXIT_INSERT_FAILED = EXIT_BASE + 5;
	static final int EXIT_REQUEST_FAILED = EXIT_BASE + 6;
	static final int EXIT_BAD_DATA = EXIT_BASE + 7;
	
	static final FRIEND_TRUST trust = FRIEND_TRUST.LOW;
	static final FRIEND_VISIBILITY visibility = FRIEND_VISIBILITY.NO;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/* Because we start a whole bunch of nodes at once, we will get many "Not reusing
	 * tracker, so wiping old trackers" messages. This is normal, all the nodes start
	 * handshaking straight off, they all send JFK(1)s, and we get race conditions. */
	
	/*
	 Borrowed from mrogers simulation code (February 6, 2008)
	 --
	 FIXME: May not generate good networks. Presumably this is because the arrays are always scanned
	        [0..n], some nodes tend to have *much* higher connections than the degree (the first few),
	        starving the latter ones.
	 */
	static void makeKleinbergNetwork (Node[] nodes, boolean idealLocations, int degree, boolean forceNeighbourConnections, RandomSource random)
	{
		if(idealLocations) {
			// First set the locations up so we don't spend a long time swapping just to stabilise each network.
			double div = 1.0 / nodes.length;
			double loc = 0.0;
			for (int i=0; i<nodes.length; i++) {
				nodes[i].setLocation(loc);
				loc += div;
			}
		}
		if(forceNeighbourConnections) {
			for(int i=0;i<nodes.length;i++) {
				int next = (i+1) % nodes.length;
				connect(nodes[i], nodes[next]);
			}
		}
		for (int i=0; i<nodes.length; i++) {
			Node a = nodes[i];
			// Normalise the probabilities
			double norm = 0.0;
			for (int j=0; j<nodes.length; j++) {
				Node b = nodes[j];
				if (a.getLocation() == b.getLocation()) continue;
				norm += 1.0 / distance (a, b);
			}
			// Create degree/2 outgoing connections
			for (int k=0; k<nodes.length; k++) {
				Node b = nodes[k];
				if (a.getLocation() == b.getLocation()) continue;
				double p = 1.0 / distance (a, b) / norm;
				for (int n = 0; n < degree / 2; n++) {
					if (random.nextFloat() < p) {
						connect(a, b);
						break;
					}
				}
			}
		}
	}
	
	static void connect(Node a, Node b) {
		try {
			a.connect (b, trust, visibility);
			b.connect (a, trust, visibility);
		} catch (FSParseException e) {
			Logger.error(RealNodeTest.class, "cannot connect!!!!", e);
		} catch (PeerParseException e) {
			Logger.error(RealNodeTest.class, "cannot connect #2!!!!", e);
		} catch (freenet.io.comm.ReferenceSignatureVerificationException e) {
			Logger.error(RealNodeTest.class, "cannot connect #3!!!!", e);
		}
	}
	
	static double distance(Node a, Node b) {
		double aL=a.getLocation();
		double bL=b.getLocation();
		return Location.distance(aL, bL);
	}
	
	static String getPortNumber(PeerNode p) {
		if (p == null || p.getPeer() == null)
			return "null";
		return Integer.toString(p.getPeer().getPort());
	}
	
	static String getPortNumber(Node n) {
		if (n == null)
			return "null";
		return Integer.toString(n.getDarknetPortNumber());
	}
	
	static void waitForAllConnected(Node[] nodes) throws InterruptedException {
		long tStart = System.currentTimeMillis();
		while(true) {
			int countFullyConnected = 0;
			int countReallyConnected = 0;
			int totalPeers = 0;
			int totalConnections = 0;
			int totalPartialConnections = 0;
			int totalCompatibleConnections = 0;
			int totalBackedOff = 0;
			double totalPingTime = 0.0;
			double maxPingTime = 0.0;
			double minPingTime = Double.MAX_VALUE;
			for(int i=0;i<nodes.length;i++) {
				int countConnected = nodes[i].peers.countConnectedDarknetPeers();
				int countAlmostConnected = nodes[i].peers.countAlmostConnectedDarknetPeers();
				int countTotal = nodes[i].peers.countValidPeers();
				int countBackedOff = nodes[i].peers.countBackedOffPeers(false);
				int countCompatible = nodes[i].peers.countCompatibleDarknetPeers();
				totalPeers += countTotal;
				totalConnections += countConnected;
				totalPartialConnections += countAlmostConnected;
				totalCompatibleConnections += countCompatible;
				totalBackedOff += countBackedOff;
				double pingTime = nodes[i].nodeStats.getNodeAveragePingTime();
				totalPingTime += pingTime;
				if(pingTime > maxPingTime) maxPingTime = pingTime;
				if(pingTime < minPingTime) minPingTime = pingTime;
				if(countConnected == countTotal) {
					countFullyConnected++;
					if(countBackedOff == 0) countReallyConnected++;
				} else {
					if(logMINOR)
						Logger.minor(RealNodeTest.class, "Connection count for "+nodes[i]+" : "+countConnected+" partial "+countAlmostConnected);
				}
				if(countBackedOff > 0) {
					if(logMINOR)
						Logger.minor(RealNodeTest.class, "Backed off: "+nodes[i]+" : "+countBackedOff);
				}
			}
			double avgPingTime = totalPingTime / nodes.length;
			if(countFullyConnected == nodes.length && countReallyConnected == nodes.length && totalBackedOff == 0 &&
					minPingTime < NodeStats.DEFAULT_SUB_MAX_PING_TIME && maxPingTime < NodeStats.DEFAULT_SUB_MAX_PING_TIME && avgPingTime < NodeStats.DEFAULT_SUB_MAX_PING_TIME) {
				System.err.println("All nodes fully connected");
				Logger.normal(RealNodeTest.class, "All nodes fully connected");
				System.err.println();
				return;
			} else {
				long tDelta = (System.currentTimeMillis() - tStart)/1000;
				System.err.println("Waiting for nodes to be fully connected: "+countFullyConnected+" / "+nodes.length+" ("+totalConnections+" / "+totalPeers+" connections total partial "+totalPartialConnections+" compatible "+totalCompatibleConnections+") - backed off "+totalBackedOff+" ping min/avg/max "+(int)minPingTime+"/"+(int)avgPingTime+"/"+(int)maxPingTime+" at "+tDelta+'s');
				Logger.normal(RealNodeTest.class, "Waiting for nodes to be fully connected: "+countFullyConnected+" / "+nodes.length+" ("+totalConnections+" / "+totalPeers+" connections total partial "+totalPartialConnections+" compatible "+totalCompatibleConnections+") - backed off "+totalBackedOff+" ping min/avg/max "+(int)minPingTime+"/"+(int)avgPingTime+"/"+(int)maxPingTime+" at "+tDelta+'s');
				Thread.sleep(1000);
			}
		}
	}

}
