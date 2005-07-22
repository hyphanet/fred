package freenet.node;

import freenet.crypt.Yarrow;
import freenet.io.comm.PeerParseException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * @author amphibian
 * 
 * When the code is invoked via this class, it:
 * - Creates two nodes.
 * - Connects them to each other
 * - Sends pings from the first node to the second node.
 * - Prints on the logger when packets are sent, when they are
 *   received, (by both sides), and their sequence numbers.
 */
public class RealNodePingTest {

    public static void main(String[] args) throws FSParseException, PeerParseException {
        Logger.setupStdoutLogging(Logger.MINOR, "");
        Yarrow yarrow = new Yarrow();
        // Create 2 nodes
        Node node1 = new Node(5001, yarrow);
        Node node2 = new Node(5002, yarrow);
        SimpleFieldSet node1ref = node1.exportFieldSet();
        SimpleFieldSet node2ref = node2.exportFieldSet();
        // Connect
        node1.peers.connect(node2ref);
        node2.peers.connect(node1ref);
        node1.start(-1);
        node2.start(-1);
        // Ping
        NodePeer pn = node1.peers.myPeers[0];
        int pingID = 0;
        node1.usm.setDropProbability(4);
        while(true) {
            Logger.minor(RealNodePingTest.class, "Sending PING "+pingID);
            boolean success = pn.ping(pingID);
            if(success)
                Logger.normal(RealNodePingTest.class, "PING "+pingID+" successful");
            else
                Logger.normal(RealNodePingTest.class, "PING FAILED: "+pingID);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                // Shouldn't happen
            }
            pingID++;
        }
    }
}
