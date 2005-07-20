package freenet.node;

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
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class RealNodePingTest {

    public static void main(String[] args) throws FSParseException, PeerParseException {
        Logger.setupStdoutLogging(Logger.MINOR, "");
        // Create 2 nodes
        Node node1 = new Node(5001);
        Node node2 = new Node(5002);
        SimpleFieldSet node1ref = node1.exportFieldSet();
        SimpleFieldSet node2ref = node2.exportFieldSet();
        // Connect
        node1.peers.connect(node2ref);
        node2.peers.connect(node1ref);
        // Ping
        NodePeer pn = node1.peers.myPeers[0];
        int pingID = 0;
        while(true) {
            boolean success = pn.ping(pingID++);
            if(success)
                Logger.normal(RealNodePingTest.class, "PING "+pingID+" successful");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                // Shouldn't happen
            }
        }
    }
}
