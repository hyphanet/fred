/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;
import java.io.IOException;

import freenet.crypt.DummyRandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientKSK;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKEncodeException;
import freenet.keys.SSKVerifyException;
import freenet.node.FSParseException;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.store.KeyCollisionException;
import freenet.support.Executor;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.SimpleFieldSet;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.io.ArrayBucket;
import freenet.support.io.FileUtil;

/**
 * Create a key block with random key and contents.
 * Create a bunch of nodes. Connect them.
 * Request the key from each node.
 * Then insert it to one of them.
 * Expected results: fast propagation via ULPRs of the data to every node.
 * 
 * This should be transformed into a Heavy Unit Test.
 * @author toad
 */
public class RealNodeULPRTest {
	
	// Exit codes
	static final int EXIT_BASE = NodeInitException.EXIT_NODE_UPPER_LIMIT;
	static final int EXIT_KEY_EXISTS = EXIT_BASE + 1;
	static final int EXIT_UNKNOWN_ERROR_CHECKING_KEY_NOT_EXIST = EXIT_BASE + 2;
	
    static final int NUMBER_OF_NODES = 10;
    static final short MAX_HTL = 5;
    //static final int NUMBER_OF_NODES = 50;
    //static final short MAX_HTL = 10;
    
    public static void main(String[] args) throws FSParseException, PeerParseException, CHKEncodeException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, KeyCollisionException, SSKEncodeException, IOException, InterruptedException, SSKVerifyException {
        System.err.println("ULPR test");
        System.err.println();
        Logger.setupStdoutLogging(Logger.ERROR, "freenet.node.Location:normal,freenet.node.simulator.RealNodeRoutingTest:normal" /*"freenet.store:minor,freenet.node.LocationManager:debug,freenet.node.FNPPacketManager:normal,freenet.io.comm.MessageCore:debug"*/);
        Logger.globalSetThreshold(Logger.ERROR);
    	String testName = "realNodeULPRTest";
        File wd = new File(testName);
        FileUtil.removeAll(wd);
        wd.mkdir();
        
        DummyRandomSource random = new DummyRandomSource();
        
        //NOTE: globalTestInit returns in ignored random source
        NodeStarter.globalTestInit(testName);
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        Executor executor = new PooledExecutor();
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i] = 
            	NodeStarter.createTestNode(5001+i, testName, false, true, true, MAX_HTL, 20 /* 5% */, random, executor);
            Logger.normal(RealNodeRoutingTest.class, "Created node "+i);
        }
        SimpleFieldSet refs[] = new SimpleFieldSet[NUMBER_OF_NODES];
        for(int i=0;i<NUMBER_OF_NODES;i++)
            refs[i] = nodes[i].exportDarknetPublicFieldSet();
        Logger.normal(RealNodeRoutingTest.class, "Created "+NUMBER_OF_NODES+" nodes");
        // Now link them up
        // Connect the set
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            int next = (i+1) % NUMBER_OF_NODES;
            int prev = (i+NUMBER_OF_NODES-1)%NUMBER_OF_NODES;
            nodes[i].connect(nodes[next]);
            nodes[i].connect(nodes[prev]);
        }
        Logger.normal(RealNodeRoutingTest.class, "Connected nodes");
        // Now add some random links
        for(int i=0;i<NUMBER_OF_NODES*5;i++) {
            if(i % NUMBER_OF_NODES == 0)
                Logger.normal(RealNodeRoutingTest.class, ""+i);
            int length = (int)Math.pow(NUMBER_OF_NODES, random.nextDouble());
            int nodeA = random.nextInt(NUMBER_OF_NODES);
            int nodeB = (nodeA+length)%NUMBER_OF_NODES;
            //System.out.println(""+nodeA+" -> "+nodeB);
            Node a = nodes[nodeA];
            Node b = nodes[nodeB];
            a.connect(b);
            b.connect(a);
        }
        
        Logger.normal(RealNodeRoutingTest.class, "Added random links");
        
        for(int i=0;i<NUMBER_OF_NODES;i++)
            nodes[i].start(false);
        
        // Now create a key.
        
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String keyName = HexUtil.bytesToHex(buf);
        FreenetURI testKey = new FreenetURI("KSK", keyName);
        
        InsertableClientSSK insertKey = InsertableClientSSK.create(testKey);
        ClientSSK fetchKey = ClientKSK.create(testKey);
        
        SSKBlock block = insertKey.encode(new ArrayBucket(buf), false, false, (short)-1, buf.length, random);
        
        System.err.println();
        System.err.println("Created random test key "+testKey);
        System.err.println();
        
        waitForAllConnected(nodes);
        
        // Fetch the key from each node.
        
        for(int i=0;i<nodes.length;i++) {
        	try {
        		nodes[i].clientCore.realGetKey(fetchKey, false, true, false);
        		System.err.println("TEST FAILED: KEY ALREADY PRESENT!!!"); // impossible!
        		System.exit(EXIT_KEY_EXISTS);
        	} catch (LowLevelGetException e) {
        		switch(e.code) {
        		case LowLevelGetException.DATA_NOT_FOUND:
        		case LowLevelGetException.ROUTE_NOT_FOUND:
        			// Expected
        			System.err.println("Node "+i+" : key not found as expected");
        			continue;
        		default:
        			System.err.println("Node "+i+" : UNEXPECTED ERROR: "+e.toString());
        			System.exit(EXIT_UNKNOWN_ERROR_CHECKING_KEY_NOT_EXIST);
        		}
        	}
        }
        
        // Now we should have a good web of subscriptions set up.
        
        // Store the key to ONE node.
        
		nodes[nodes.length-1].store(block, false);
        
        // Every 5 seconds, check how many nodes have the key.
		
		int x = -1;
		while(true) {
			x++;
			Thread.sleep(1000);
			int count = 0;
			for(int i=0;i<nodes.length;i++) {
				if(nodes[i].fetch(fetchKey, true) != null)
					count++;
			}
			System.err.println("T="+x+" : "+count+'/'+nodes.length+" have the data.");
		}
        
    }
    
    // FIXME factor out to some simulator utility class.
	private static void waitForAllConnected(Node[] nodes) throws InterruptedException {
		while(true) {
			int countFullyConnected = 0;
			int totalPeers = 0;
			int totalConnections = 0;
			for(int i=0;i<nodes.length;i++) {
				int countConnected = nodes[i].peers.countConnectedDarknetPeers();
				int countTotal = nodes[i].peers.countValidPeers();
				totalPeers += countTotal;
				totalConnections += countConnected;
				if(countConnected == countTotal)
					countFullyConnected++;
			}
			if(countFullyConnected == nodes.length) {
				System.err.println("All nodes fully connected");
				System.err.println();
				return;
			} else {
				System.err.println("Waiting for nodes to be fully connected: "+countFullyConnected+" / "+nodes.length+" ("+totalConnections+" / "+totalPeers+" connections total)");
				Thread.sleep(1000);
			}
		}
	}


}
