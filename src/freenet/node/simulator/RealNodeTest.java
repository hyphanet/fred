/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import freenet.io.comm.PeerParseException;
import freenet.node.FSParseException;
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Logger;

/**
 * Optional base class for RealNode*Test.
 * Has some useful utilities.
 * @author toad
 * @author robert
 */
public class RealNodeTest {

	/*
	 Borrowed from mrogers simulation code (February 6, 2008)
	 */
	static void makeKleinbergNetwork (Node[] nodes, boolean idealLocations, int degree, boolean forceNeighbourConnections)
	{
		// First set the locations up so we don't spend a long time swapping just to stabilise each network.
		double div = 1.0 / (nodes.length + 1);
		double loc = 0.0;
		for (int i=0; i<nodes.length; i++) {
			nodes[i].setLocation(loc);
			loc += div;
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
					if (Math.random() < p) {
						connect(a, b);
						break;
					}
				}
			}
		}
	}
	
	static void connect(Node a, Node b) {
		try {
			a.connect (b);
			b.connect (a);
		} catch (FSParseException e) {
			Logger.error(RealNodeSecretPingTest.class, "cannot connect!!!!", e);
		} catch (PeerParseException e) {
			Logger.error(RealNodeSecretPingTest.class, "cannot connect #2!!!!", e);
		} catch (freenet.io.comm.ReferenceSignatureVerificationException e) {
			Logger.error(RealNodeSecretPingTest.class, "cannot connect #3!!!!", e);
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
	
	
}
