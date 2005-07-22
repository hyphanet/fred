package freenet.node;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.Yarrow;
import freenet.io.comm.PeerParseException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * @author amphibian
 * 
 * Create a mesh of nodes and let them sort out their locations.
 * 
 * Then run some node-to-node searches.
 */
public class RealNodeRoutingTest {

    static final int NUMBER_OF_NODES = 200;
    
    public static void main(String[] args) throws FSParseException, PeerParseException {
        Logger.setupStdoutLogging(Logger.NORMAL, "freenet.node.LocationManager:debug");
        System.out.println("Routing test using real nodes:");
        System.out.println();
        DummyRandomSource random = new DummyRandomSource();
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i] = new Node(5000+i, random);
            Logger.normal(RealNodeRoutingTest.class, "Created node "+i);
        }
        SimpleFieldSet refs[] = new SimpleFieldSet[NUMBER_OF_NODES];
        for(int i=0;i<NUMBER_OF_NODES;i++)
            refs[i] = nodes[i].exportFieldSet();
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
            a.peers.connect(b.exportFieldSet());
            b.peers.connect(a.exportFieldSet());
        }

        Logger.normal(RealNodeRoutingTest.class, "Added random links");
        
        for(int i=0;i<NUMBER_OF_NODES;i++)
            nodes[i].start();
        
        // Now sit back and watch the fireworks!
        int cycleNumber = 0;
        int lastSwaps = 0;
        int lastNoSwaps = 0;
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
            lastSwaps = newSwaps;
        }
    }
}
