/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;

import freenet.crypt.DiffieHellman;
import freenet.crypt.DummyRandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.node.FSParseException;
import freenet.node.LocationManager;
import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.node.Node.NodeInitException;
import freenet.support.Logger;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;

/**
 * @author amphibian
 * 
 * Create a mesh of nodes and let them sort out their locations.
 * 
 * Then run some node-to-node searches.
 */
public class RealNodeRoutingTest {

    static final int NUMBER_OF_NODES = 150;
    static final short MAX_HTL = (short)6;
    
    public static void main(String[] args) throws FSParseException, PeerParseException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException {
        Logger.setupStdoutLogging(Logger.NORMAL, "freenet.node.CPUAdjustingSwapRequestInterval:minor" /*"freenet.node.LocationManager:debug,freenet.node.FNPPacketManager:normal,freenet.io.comm.MessageCore:debug"*/);
        System.out.println("Routing test using real nodes:");
        System.out.println();
        String wd = "realNodeRequestInsertTest";
        new File(wd).mkdir();
        NodeStarter.globalTestInit(wd); // ignore Random, using our own
        DummyRandomSource random = new DummyRandomSource();
        DiffieHellman.init(random);
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i] = 
            	NodeStarter.createTestNode(5001+i, wd, false, true, true, MAX_HTL, 0 /* no dropped packets */, /* Node.DEFAULT_SWAP_INTERVAL */ 0, random);
            Logger.normal(RealNodeRoutingTest.class, "Created node "+i);
        }
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
        
        // Now sit back and watch the fireworks!
        int cycleNumber = 0;
        int lastSwaps = 0;
        int lastNoSwaps = 0;
        int failures = 0;
        int successes = 0;
        RunningAverage avg = new SimpleRunningAverage(100, 0.0);
        RunningAverage avg2 = new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 100, null);
        int pings = 0;
        while(true) {
            cycleNumber++;
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // Ignore
            }
            for(int i=0;i<NUMBER_OF_NODES;i++) {
                Logger.normal(RealNodeRoutingTest.class, "Cycle "+cycleNumber+" node "+i+": "+nodes[i].getLocation());
            }
            int newSwaps = LocationManager.swaps;
            int totalStarted = LocationManager.startedSwaps;
            int noSwaps = LocationManager.noSwaps;
            Logger.normal(RealNodeRoutingTest.class, "Swaps: "+(newSwaps-lastSwaps));
            Logger.normal(RealNodeRoutingTest.class, "\nTotal swaps: Started*2: "+totalStarted*2+", succeeded: "+newSwaps+", last minute failures: "+noSwaps+
                    ", ratio "+(double)noSwaps/(double)newSwaps+", early failures: "+((totalStarted*2)-(noSwaps+newSwaps)));
            Logger.normal(RealNodeRoutingTest.class, "This cycle ratio: "+((double)(noSwaps-lastNoSwaps)) / ((double)(newSwaps - lastSwaps)));
            lastNoSwaps = noSwaps;
            Logger.normal(RealNodeRoutingTest.class, "Swaps rejected (already locked): "+LocationManager.swapsRejectedAlreadyLocked);
            Logger.normal(RealNodeRoutingTest.class, "Swaps rejected (nowhere to go): "+LocationManager.swapsRejectedNowhereToGo);
            Logger.normal(RealNodeRoutingTest.class, "Swaps rejected (rate limit): "+LocationManager.swapsRejectedRateLimit);
            Logger.normal(RealNodeRoutingTest.class, "Swaps rejected (loop): "+LocationManager.swapsRejectedLoop);
            Logger.normal(RealNodeRoutingTest.class, "Swaps rejected (recognized ID):" +LocationManager.swapsRejectedRecognizedID);
            lastSwaps = newSwaps;
            // Do some (routed) test-pings
            for(int i=0;i<10;i++) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e1) {
                }
                try {
                Node randomNode = nodes[random.nextInt(NUMBER_OF_NODES)];
                Node randomNode2 = randomNode;
                while(randomNode2 == randomNode)
                    randomNode2 = nodes[random.nextInt(NUMBER_OF_NODES)];
                Logger.normal(RealNodeRoutingTest.class, "Pinging "+randomNode2.getDarknetPortNumber()+" from "+randomNode.getDarknetPortNumber());
                double loc2 = randomNode2.getLocation();
                int hopsTaken = randomNode.routedPing(loc2);
                pings++;
                if(hopsTaken < 0) {
                    failures++;
                    avg.report(0.0);
                    avg2.report(0.0);
                    double ratio = (double)successes / ((double)(failures+successes));
                    Logger.normal(RealNodeRoutingTest.class, "Routed ping "+pings+" FAILED from "+randomNode.getDarknetPortNumber()+" to "+randomNode2.getDarknetPortNumber()+" (long:"+ratio+", short:"+avg.currentValue()+", vague:"+avg2.currentValue()+ ')');
                } else {
                    successes++;
                    avg.report(1.0);
                    avg2.report(1.0);
                    double ratio = (double)successes / ((double)(failures+successes));
                    Logger.normal(RealNodeRoutingTest.class, "Routed ping "+pings+" success: "+hopsTaken+ ' ' +randomNode.getDarknetPortNumber()+" to "+randomNode2.getDarknetPortNumber()+" (long:"+ratio+", short:"+avg.currentValue()+", vague:"+avg2.currentValue()+ ')');
                }
                } catch (Throwable t) {
                    Logger.error(RealNodeRoutingTest.class, "Caught "+t, t);
                }
            }
        }
    }
}
