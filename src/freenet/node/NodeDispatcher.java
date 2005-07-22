package freenet.node;

import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;

/**
 * @author amphibian
 * 
 * Dispatcher for unmatched FNP messages.
 * 
 * What can we get?
 * 
 * SwapRequests
 * 
 * DataRequests
 * 
 * InsertRequests
 * 
 * Probably a few others; those are the important bits.
 * 
 * Note that if in response to these we have to send a packet,
 * IT MUST BE DONE OFF-THREAD. Because sends will normally block.
 */
public class NodeDispatcher implements Dispatcher {

    final Node node;
    
    NodeDispatcher(Node node) {
        this.node = node;
    }
    
    public boolean handleMessage(Message m) {
        NodePeer source = (NodePeer)m.getSource();
        if(m.getSpec() == DMT.FNPPing) {
            // Send an FNPPong
            Message reply = DMT.createFNPPong(m.getInt(DMT.PING_SEQNO));
            ((NodePeer)m.getSource()).sendAsync(reply);
            return true;
        }
        if(m.getSpec() == DMT.FNPLocChangeNotification) {
            double newLoc = m.getDouble(DMT.LOCATION);
            source.updateLocation(newLoc);
            return true;
        }
        if(m.getSpec() == DMT.FNPSwapRequest) {
            return node.lm.handleSwapRequest(m);
        }
        if(m.getSpec() == DMT.FNPSwapReply) {
            return node.lm.handleSwapReply(m);
        }
        if(m.getSpec() == DMT.FNPSwapRejected) {
            return node.lm.handleSwapRejected(m);
        }
        if(m.getSpec() == DMT.FNPSwapCommit) {
            return node.lm.handleSwapCommit(m);
        }
        if(m.getSpec() == DMT.FNPSwapComplete) {
            return node.lm.handleSwapComplete(m);
        }
        return false;
    }

}
