package freenet.node;

import java.io.File;

import freenet.crypt.DiffieHellman;
import freenet.crypt.DummyRandomSource;
import freenet.io.comm.PeerParseException;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
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
    
    public static void main(String[] args) throws FSParseException, PeerParseException {
        PeerNode.disableProbabilisticHTLs = true;
        Node.MAX_HTL = 6;
        FileLoggerHook fh = Logger.setupStdoutLogging(Logger.NORMAL, "freenet.node.CPUAdjustingSwapRequestInterval:minor" /*"freenet.node.LocationManager:debug,freenet.node.FNPPacketManager:normal,freenet.io.comm.UdpSocketManager:debug"*/);
        System.out.println("Routing test using real nodes:");
        System.out.println();
        String wd = "realNodeRequestInsertTest";
        new File(wd).mkdir();
        DummyRandomSource random = new DummyRandomSource();
        DiffieHellman.init(random);
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i] = new Node(5000+i, random, null, wd+File.separator, 0, false, fh, 0);
            Logger.normal(RealNodeRoutingTest.class, "Created node "+i);
        }
        SimpleFieldSet refs[] = new SimpleFieldSet[NUMBER_OF_NODES];
        for(int i=0;i<NUMBER_OF_NODES;i++)
            refs[i] = nodes[i].exportPublicFieldSet();
        Logger.normal(RealNodeRoutingTest.class, "Created "+NUMBER_OF_NODES+" nodes");
        // Now link them up
        // Connect the set
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            int next = (i+1) % NUMBER_OF_NODES;
            int prev = (i+NUMBER_OF_NODES-1)%NUMBER_OF_NODES;
            nodes[i].peers.connect(refs[next]);
            nodes[i].peers.connect(refs[prev]);
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
            a.peers.connect(b.exportPublicFieldSet());
            b.peers.connect(a.exportPublicFieldSet());
        }

        Logger.normal(RealNodeRoutingTest.class, "Added random links");
        
        SwapRequestInterval sri =
            new CPUAdjustingSwapRequestInterval(((500*1000*NUMBER_OF_NODES)/200), 50);
        
        for(int i=0;i<NUMBER_OF_NODES;i++)
            nodes[i].start(sri);
        
        // Now sit back and watch the fireworks!
        int cycleNumber = 0;
        int lastSwaps = 0;
        int lastNoSwaps = 0;
        int failures = 0;
        int successes = 0;
        RunningAverage avg = new SimpleRunningAverage(100, 0.0);
        RunningAverage avg2 = new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 100);
        int pings = 0;
        while(true) {
            cycleNumber++;
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // Ignore
            }
            for(int i=0;i<NUMBER_OF_NODES;i++) {
                Logger.normal(RealNodeRoutingTest.class, "Cycle "+cycleNumber+" node "+i+": "+nodes[i].lm.getLocation().getValue());
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
                Logger.normal(RealNodeRoutingTest.class, "Pinging "+randomNode2.portNumber+" from "+randomNode.portNumber);
                double loc2 = randomNode2.lm.getLocation().getValue();
                int hopsTaken = randomNode.routedPing(loc2);
                pings++;
                if(hopsTaken < 0) {
                    failures++;
                    avg.report(0.0);
                    avg2.report(0.0);
                    double ratio = (double)successes / ((double)(failures+successes));
                    Logger.normal(RealNodeRoutingTest.class, "Routed ping "+pings+" FAILED from "+randomNode.portNumber+" to "+randomNode2.portNumber+" (long:"+ratio+", short:"+avg.currentValue()+", vague:"+avg2.currentValue()+")");
                } else {
                    successes++;
                    avg.report(1.0);
                    avg2.report(1.0);
                    double ratio = (double)successes / ((double)(failures+successes));
                    Logger.normal(RealNodeRoutingTest.class, "Routed ping "+pings+" success: "+hopsTaken+" "+randomNode.portNumber+" to "+randomNode2.portNumber+" (long:"+ratio+", short:"+avg.currentValue()+", vague:"+avg2.currentValue()+")");
                }
                } catch (Throwable t) {
                    Logger.error(RealNodeRoutingTest.class, "Caught "+t, t);
                }
            }
        }
    }
}
