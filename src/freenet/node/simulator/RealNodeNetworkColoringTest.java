/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;

import freenet.crypt.DummyRandomSource;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.node.FSParseException;
import freenet.node.Location;
import freenet.node.LocationManager;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.PeerNode;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;

/**
 * Create a bunch of nodes
 * Connect them into two or more s.w. networks (rather than just letting them sort out their locations)
 * Weakly connect the two networks.
 * See if they will be able to separate themselves.
 */
public class RealNodeNetworkColoringTest {

    //static final int NUMBER_OF_NODES = 150;
	static final int NUMBER_OF_NODES = 20;
	static final int BRIDGES = 3;
	
	//either the number of connections between the two networks (if BRIDGES=0)
	//or the number of connections from each bridge to each network (if BRIDGES>0)
	static final int BRIDGE_LINKS = 2;
	
    static final short MAX_HTL = (short)6;
	static final int DEGREE = 5;
	
	static final long storeSize = 1024*1024;
	
	//Use something shorter than 'freenet.node.simulator.RealNodeNetworkColoringTest' !
	private static final Object log = new Object();
	
    public static void main(String[] args) throws FSParseException, PeerParseException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException {
        //Logger.setupStdoutLogging(Logger.NORMAL, "freenet.node.CPUAdjustingSwapRequestInterval:minor" /*"freenet.node.LocationManager:debug,freenet.node.FNPPacketManager:normal,freenet.io.comm.MessageCore:debug"*/);
        System.out.println("SecretPing/NetworkColoring test using real nodes:");
        System.out.println();
        String wd = "realNodeNetworkColorTest";
        new File(wd).mkdir();
        //NOTE: globalTestInit returns in ignored random source
        NodeStarter.globalTestInit(wd, false);
		Logger.setupStdoutLogging(Logger.ERROR, "freenet.node.Location:normal,freenet.node.simulator.RealNodeNetworkColoringTest:normal,freenet.node.NetworkIDManager:normal");
		Logger.globalSetThreshold(Logger.ERROR);

        DummyRandomSource random = new DummyRandomSource();
        //DiffieHellman.init(random);
        Node[] subnetA = new Node[NUMBER_OF_NODES];
		Node[] subnetB = new Node[NUMBER_OF_NODES];
		Node[] bridges = new Node[BRIDGES];
		
		int totalNodes=NUMBER_OF_NODES*2+BRIDGES;
		Node[] allNodes = new Node[totalNodes];
		
		//cheat and use totalNodes as a counter for a moment...
		totalNodes=0;
		
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        Executor executor = new PooledExecutor();
		
		//Allow secret pings, and send them automatically, must be done before creating the nodes.
		freenet.node.NetworkIDManager.disableSecretPings=false;
		freenet.node.NetworkIDManager.disableSecretPinger=false;
		
        for(int i=0;i<NUMBER_OF_NODES;i++) {
			allNodes[totalNodes] =
            subnetA[i] = 
            	NodeStarter.createTestNode(5001+totalNodes, wd, false, true, true, MAX_HTL, 0 /* no dropped packets */, random, executor, 500*NUMBER_OF_NODES, storeSize, true);
			totalNodes++;
            Logger.normal(RealNodeRoutingTest.class, "Created 'A' node "+totalNodes);
        }
        for(int i=0;i<NUMBER_OF_NODES;i++) {
			allNodes[totalNodes] =
            subnetB[i] = 
			NodeStarter.createTestNode(5001+totalNodes, wd, false, true, true, MAX_HTL, 0 /* no dropped packets */, random, executor, 500*NUMBER_OF_NODES, storeSize, true);
			totalNodes++;
            Logger.normal(RealNodeRoutingTest.class, "Created 'B' node "+totalNodes);
        }
		for(int i=0;i<BRIDGES;i++) {
			allNodes[totalNodes] =
            bridges[i] = 
			NodeStarter.createTestNode(5001+totalNodes, wd, false, true, true, MAX_HTL, 0 /* no dropped packets */, random, executor, 500*NUMBER_OF_NODES, storeSize, true);
			totalNodes++;
            Logger.normal(RealNodeRoutingTest.class, "Created bridge node "+totalNodes);
        }
        
        Logger.normal(RealNodeRoutingTest.class, "Created "+totalNodes+" nodes");
		
        // Now link them up
        makeKleinbergNetwork(subnetA);
		makeKleinbergNetwork(subnetB);
		
        Logger.normal(RealNodeRoutingTest.class, "Added small-world links, weakly connect the subnets");
        
		if (BRIDGES==0) {
			for (int i=0; i<BRIDGE_LINKS; i++) {
				//connect a random node from A to a random node from B
				Node a = subnetA[random.nextInt(NUMBER_OF_NODES)];
				Node b = subnetB[random.nextInt(NUMBER_OF_NODES)];
				connect(a, b);
			}
		} else {
			for (int b=0; b<BRIDGES; b++) {
				Node bridge=bridges[b];
				//make BRIDGE_LINKS into A
				for (int i=0; i<BRIDGE_LINKS; i++) {
					Node nodeA = subnetA[random.nextInt(NUMBER_OF_NODES)];
					connect(bridge, nodeA);
				}
				//make BRIDGE_LINKS into B
				for (int i=0; i<BRIDGE_LINKS; i++) {
					Node nodeB = subnetB[random.nextInt(NUMBER_OF_NODES)];
					connect(bridge, nodeB);
				}
			}
		}
		
		for(int i=0;i<totalNodes;i++)
            allNodes[i].start(false);
		
        // Now sit back and watch the fireworks!
        int cycleNumber = 0;
        int lastSwaps = 0;
        int lastNoSwaps = 0;
        int failures = 0;
        int successes = 0;
		RunningAverage general = new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 2000, null);
        RunningAverage aRate = new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 500, null);
		RunningAverage bRate = new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 500, null);
		RunningAverage bridgeRate = new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 500, null);
		HashSet generalIds=new HashSet();
		HashSet aIds=new HashSet();
		HashSet bIds=new HashSet();
		HashSet bridgeIds=new HashSet();
        int pings = 0;
		
        while(true) {
            cycleNumber++;
			
			try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // Ignore
            }
			
			long totalSuccesses=0;
			long totalTotalPings=0;
			generalIds.clear();
			aIds.clear();
			bIds.clear();
			bridgeIds.clear();
			
			for (int i=0; i<NUMBER_OF_NODES; i++) {
				long good=subnetA[i].netid.secretPingSuccesses;
				long total=subnetA[i].netid.totalSecretPingAttempts;
				int id=subnetA[i].netid.ourNetworkId;
				totalSuccesses+=good;
				totalTotalPings+=total;
				//eh... not really, but I guess it's close; reset this nodes good/total?
				double rate = 0.0;
				if (total!=0)
					rate = 1.0*good/total;
				general.report(rate);
				aRate.report(rate);
				generalIds.add(new Integer(id));
				aIds.add(new Integer(id));
			}
			
			for (int i=0; i<NUMBER_OF_NODES; i++) {
				long good=subnetB[i].netid.secretPingSuccesses;
				long total=subnetB[i].netid.totalSecretPingAttempts;
				int id=subnetB[i].netid.ourNetworkId;
				totalSuccesses+=good;
				totalTotalPings+=total;
				//eh... not really, but I guess it's close; reset this nodes good/total?
				double rate = 0.0;
				if (total!=0)
					rate = 1.0*good/total;
				general.report(rate);
				bRate.report(rate);
				generalIds.add(new Integer(id));
				bIds.add(new Integer(id));
			}
			
			for (int i=0; i<BRIDGES; i++) {
				long good=bridges[i].netid.secretPingSuccesses;
				long total=bridges[i].netid.totalSecretPingAttempts;
				int id=bridges[i].netid.ourNetworkId;
				totalSuccesses+=good;
				totalTotalPings+=total;
				//eh... not really, but I guess it's close; reset this nodes good/total?
				double rate = 0.0;
				if (total!=0)
					rate = 1.0*good/total;
				general.report(rate);
				bridgeRate.report(rate);
				generalIds.add(new Integer(id));
				bridgeIds.add(new Integer(id));
			}
			
			Logger.error(log, "cycle = "+cycleNumber);
			Logger.error(log, "total SecretPings= "+totalTotalPings);
			Logger.error(log, "total successful = "+totalSuccesses);
			
			Logger.error(log, "  pSuccess(All)  = "+general.currentValue());
			Logger.error(log, "  pSuccess( A )  = "+aRate.currentValue());
			Logger.error(log, "  pSuccess( B )  = "+bRate.currentValue());
			if (BRIDGES!=0)
				Logger.error(log, "  pSuccess(BRG)  = "+bridgeRate.currentValue());
			
			idReport("All", generalIds);
			idReport(" A ", generalIds);
			idReport(" B ", generalIds);
			if (BRIDGES!=0)
				idReport("BRG", generalIds);
		}
    }
	
	private static void idReport(String group, HashSet ids) {
		//Print out the number which are non-zero & display the distinct ones if a few...
		int size=ids.size();
		int MAX=4;
		StringBuffer sb=new StringBuffer(Integer.toString(size)).append(" ids (").append(group).append(") = ");
		Iterator iter=ids.iterator();
		for (int i=0; i<MAX && i<size; i++) {
			String thisId=iter.next().toString();
			if (i==0)
				sb.append(thisId);
			else
				sb.append(", ").append(thisId);
		}
		if (size>MAX)
			sb.append(", ...");
		Logger.error(log, sb.toString());
	}
	
	/*
	 Borrowed from mrogers simulation code (February 6, 2008)
	 */
	static void makeKleinbergNetwork (Node[] nodes)
	{
		for (int i=0; i<nodes.length; i++) {
			Node a = nodes[i];
			// Normalise the probabilities
			double norm = 0.0;
			for (int j=0; j<nodes.length; j++) {
				Node b = nodes[j];
				if (a.getLocation() == b.getLocation()) continue;
				norm += 1.0 / distance (a, b);
			}
			// Create DEGREE/2 outgoing connections
			for (int k=0; k<nodes.length; k++) {
				Node b = nodes[k];
				if (a.getLocation() == b.getLocation()) continue;
				double p = 1.0 / distance (a, b) / norm;
				for (int n = 0; n < DEGREE / 2; n++) {
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
