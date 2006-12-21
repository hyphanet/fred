/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;
import java.util.Arrays;

import freenet.crypt.DiffieHellman;
import freenet.crypt.DummyRandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.node.FSParseException;
import freenet.node.LocationManager;
import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.node.Node.NodeInitException;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;

/**
 * @author amphibian
 */
public class RealNodeRequestInsertTest {

    static final int NUMBER_OF_NODES = 10;
    static final short MAX_HTL = 5;
    
    public static void main(String[] args) throws FSParseException, PeerParseException, CHKEncodeException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException {
        String wd = "realNodeRequestInsertTest";
        new File(wd).mkdir();
        NodeStarter.globalTestInit(wd); // ignore Random, using our own
        // Don't clobber nearby nodes!
        Logger.setupStdoutLogging(Logger.DEBUG, "freenet.store:minor,freenet.node.Location:normal" /*"freenet.node.LocationManager:debug,freenet.node.FNPPacketManager:normal,freenet.io.comm.UdpSocketManager:debug"*/);
        Logger.globalSetThreshold(Logger.DEBUG);
        System.out.println("Insert/retrieve test");
        System.out.println();
        DummyRandomSource random = new DummyRandomSource();
        DiffieHellman.init(random);
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i] = 
            	NodeStarter.createTestNode(5001+i, wd, false, true, true, MAX_HTL, 20 /* 5% */, Node.DEFAULT_SWAP_INTERVAL, random);
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
                    Thread.sleep(2000);
                } catch (InterruptedException e1) {
                }
                try {
                Node randomNode = nodes[random.nextInt(NUMBER_OF_NODES)];
                Node randomNode2 = randomNode;
                while(randomNode2 == randomNode)
                    randomNode2 = nodes[random.nextInt(NUMBER_OF_NODES)];
                Logger.normal(RealNodeRoutingTest.class, "Pinging "+randomNode2.getPortNumber()+" from "+randomNode.getPortNumber());
                double loc2 = randomNode2.getLocation();
                int hopsTaken = randomNode.routedPing(loc2);
                pings++;
                if(hopsTaken < 0) {
                    failures++;
                    avg.report(0.0);
                    avg2.report(0.0);
                    double ratio = (double)successes / ((double)(failures+successes));
                    Logger.normal(RealNodeRoutingTest.class, "Routed ping "+pings+" FAILED from "+randomNode.getPortNumber()+" to "+randomNode2.getPortNumber()+" (long:"+ratio+", short:"+avg.currentValue()+", vague:"+avg2.currentValue()+ ')');
                } else {
                    successes++;
                    avg.report(1.0);
                    avg2.report(1.0);
                    double ratio = (double)successes / ((double)(failures+successes));
                    Logger.normal(RealNodeRoutingTest.class, "Routed ping "+pings+" success: "+hopsTaken+ ' ' +randomNode.getPortNumber()+" to "+randomNode2.getPortNumber()+" (long:"+ratio+", short:"+avg.currentValue()+", vague:"+avg2.currentValue()+ ')');
                }
                } catch (Throwable t) {
                    Logger.error(RealNodeRoutingTest.class, "Caught "+t, t);
                }
            }
            if(pings > 10 && avg.currentValue() > 0.98 && ((double)successes / ((double)(failures+successes)) > 0.98)) {
                break;
            }
        }
        System.out.println();
        System.out.println("Ping average > 98%, lets do some inserts/requests");
        System.out.println();
        int requestNumber = 0;
        RunningAverage requestsAvg = new SimpleRunningAverage(100, 0.0);
        String baseString = System.currentTimeMillis() + " ";
        while(true) {
            try {
                requestNumber++;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e1) {
                }
                String dataString = baseString + requestNumber;
                // Pick random node to insert to
                int node1 = random.nextInt(NUMBER_OF_NODES);
                Node randomNode = nodes[node1];
                Logger.error(RealNodeRequestInsertTest.class,"Inserting: \""+dataString+"\" to "+node1);
                byte[] data = dataString.getBytes();
                ClientCHKBlock block;
                block = ClientCHKBlock.encode(data, false, false, (short)-1, 0);
                ClientCHK chk = (ClientCHK) block.getClientKey();
                byte[] encData = block.getData();
                byte[] encHeaders = block.getHeaders();
                ClientCHKBlock newBlock = new ClientCHKBlock(encData, encHeaders, chk, true);
                Logger.error(RealNodeRequestInsertTest.class, "Decoded: "+new String(newBlock.memoryDecode()));
                Logger.error(RealNodeRequestInsertTest.class,"CHK: "+chk.getURI());
                Logger.error(RealNodeRequestInsertTest.class,"Headers: "+HexUtil.bytesToHex(block.getHeaders()));
                randomNode.clientCore.realPut(block, true);
                Logger.error(RealNodeRequestInsertTest.class, "Inserted to "+node1);
                Logger.error(RealNodeRequestInsertTest.class, "Data: "+Fields.hashCode(encData)+", Headers: "+Fields.hashCode(encHeaders));
                // Pick random node to request from
                int node2;
                do {
                    node2 = random.nextInt(NUMBER_OF_NODES);
                } while(node2 == node1);
                Node fetchNode = nodes[node2];
                block = (ClientCHKBlock) fetchNode.clientCore.realGetKey((ClientKey) chk, false, true, false);
                if(block == null) {
                    Logger.error(RealNodeRequestInsertTest.class, "Fetch FAILED from "+node2);
                    requestsAvg.report(0.0);
                } else {
                    byte[] results = block.memoryDecode();
                    requestsAvg.report(1.0);
                    if(Arrays.equals(results, data)) {
                        Logger.error(RealNodeRequestInsertTest.class, "Fetch succeeded: "+new String(results));
                    } else {
                        Logger.error(RealNodeRequestInsertTest.class, "Returned invalid data!: "+new String(results));
                    }
                }
            } catch (Throwable t) {
                Logger.error(RealNodeRequestInsertTest.class, "Caught "+t, t);
            }
        }
    }
}
